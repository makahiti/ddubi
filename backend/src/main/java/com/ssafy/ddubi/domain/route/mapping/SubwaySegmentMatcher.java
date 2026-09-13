package com.ssafy.ddubi.domain.route.mapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 카카오 SUBWAY step을 정적 공식 topology의 인접 역 구간으로 변환한다.
 *
 * <p>canonical ID는 시간표 마스터의 정적 호선·역 ID로 고정한다. 서울 실시간 API의
 * {@code subwayId/statnId}는 별도 crosswalk로만 전달하며, crosswalk가 나중에 보강되어도
 * 기존 baseline·Redis key가 바뀌지 않는다. 따라서 topology가 하나로 확정되면 EXACT이고,
 * 실시간 조회 가능 여부는 각 {@link MappedTransitSegment#realtimeLookupReady()}로 따로 판단한다.</p>
 *
 * <p>별칭은 공식 이름으로 문자열 치환하지 않는다. 검증된 alias가 있으면 해당 정적
 * canonical ID 후보만 허용하고, 없을 때만 기본 정규화 이름으로 후보를 찾는다.</p>
 */
public class SubwaySegmentMatcher {

    private final TransitNameNormalizer normalizer;
    private final Map<String, List<SubwayLineMaster>> linesByNormalizedName;
    private final Map<String, List<SubwayLineMaster>> linesById;
    private final Map<String, Set<String>> lineAliasIds;
    private final Map<String, Set<String>> stationAliasIds;

    public SubwaySegmentMatcher(Collection<SubwayLineMaster> lines) {
        this(lines, new TransitNameNormalizer(), Map.of(), Map.of());
    }

    public SubwaySegmentMatcher(
            Collection<SubwayLineMaster> lines,
            TransitNameNormalizer normalizer
    ) {
        this(lines, normalizer, Map.of(), Map.of());
    }

    /**
     * @param lineAliasIds 기본 정규화된 provider 호선명 → 정적 subwayId 후보 집합
     * @param stationAliasIds 기본 정규화된 provider 역명 → 호선별 정적 stationId 후보 집합
     */
    public SubwaySegmentMatcher(
            Collection<SubwayLineMaster> lines,
            TransitNameNormalizer normalizer,
            Map<String, Set<String>> lineAliasIds,
            Map<String, Set<String>> stationAliasIds
    ) {
        this.normalizer = Objects.requireNonNull(normalizer);
        this.lineAliasIds = normalizedAliasIds(lineAliasIds, true);
        this.stationAliasIds = normalizedAliasIds(stationAliasIds, false);
        List<SubwayLineMaster> safeLines = lines == null ? List.of() : List.copyOf(lines);
        this.linesByNormalizedName = indexLinesByName(safeLines);
        this.linesById = indexLinesById(safeLines);
    }

    /** 공식 호선·방향·역 경로가 하나일 때만 EXACT와 인접 역 segment 목록을 반환한다. */
    public SubwayMappingResult match(SubwayMappingRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // 1단계: 카카오 호선 표기와 검증 별칭을 정적 subwayId 후보로 바꾼다.
        // 실시간 subwayId는 topology 식별자가 아니므로 이 후보 검색에 사용하지 않는다.
        LineLookup lookup = findLineCandidates(request.providerLineNames());
        if (lookup.normalizedProviderNames().isEmpty()) {
            return failed(SubwayMappingStatus.UNMAPPED, "PROVIDER_LINE_NOT_PROVIDED", 0);
        }
        if (lookup.unresolvedProviderCount() > 0 && lookup.normalizedProviderNames().size() > 1) {
            return failed(SubwayMappingStatus.PARTIAL,
                    "PROVIDER_LINE_COVERAGE_INCOMPLETE", lookup.lines().size());
        }
        if (lookup.lines().isEmpty()) {
            return failed(SubwayMappingStatus.UNMAPPED, "LINE_NOT_FOUND", 0);
        }
        if (lookup.distinctLineIds() > 1) {
            return failed(SubwayMappingStatus.AMBIGUOUS,
                    lookup.normalizedProviderNames().size() > 1
                            ? "MULTIPLE_PROVIDER_LINES"
                            : "LINE_NAME_AMBIGUOUS",
                    lookup.lines().size());
        }

        // 2단계: 같은 호선의 상·하행, 본선·지선·단축 패턴을 각각 독립적으로 탐색한다.
        // 하나로 합치면 실제로 이어지지 않는 두 역을 인접 구간으로 만드는 오류가 생긴다.
        List<Candidate> candidates = new ArrayList<>();
        for (SubwayLineMaster line : lookup.lines()) {
            candidates.addAll(matchStations(line, request));
        }
        if (candidates.isEmpty()) {
            return partialLineOnly(lookup.lines(), "STATION_OR_FORWARD_ORDER_NOT_FOUND", 0);
        }

        // 3단계: 카카오가 준 전체 역 순서가 공식 부분경로와 일치하는 후보만 EXACT 대상으로 본다.
        // 정렬은 결정론을 위한 것이며 서로 다른 topology identity를 동점 해소로 선택하지 않는다.
        candidates.sort(Comparator
                .comparingDouble(Candidate::passingRatio).reversed()
                .thenComparing(candidate -> candidate.line().subwayId())
                .thenComparing(candidate -> candidate.line().direction())
                .thenComparing(Candidate::identity)
                .thenComparing(candidate -> candidate.line().servicePatternId()));
        List<Candidate> exactCandidates = candidates.stream()
                .filter(candidate -> candidate.passingRatio() == 1.0)
                .toList();
        // subwayId·방향·전체 stationId 경로가 같을 때만 서비스 패턴 차이를 같은 결과로 접는다.
        long exactIdentities = exactCandidates.stream().map(Candidate::identity).distinct().count();
        if (exactIdentities > 1) {
            return failed(SubwayMappingStatus.AMBIGUOUS,
                    "MULTIPLE_TOPOLOGY_PATHS", candidates.size());
        }
        if (exactIdentities == 0) {
            return partialLineOnly(lookup.lines(), "PASSING_STATIONS_NOT_CONFIRMED", candidates.size());
        }
        return exact(exactCandidates.get(0), candidates.size());
    }

    /** provider 별칭의 영향을 받지 않는 공식 호선명 인덱스를 만든다. */
    private Map<String, List<SubwayLineMaster>> indexLinesByName(
            Collection<SubwayLineMaster> lines
    ) {
        Map<String, List<SubwayLineMaster>> index = new HashMap<>();
        for (SubwayLineMaster line : validLines(lines)) {
            String key = normalizer.normalizeSubwayLineName(line.lineName());
            if (!key.isEmpty()) {
                index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(line);
            }
        }
        index.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(index);
    }

    /** 같은 정적 호선의 방향·본선·지선·단축 패턴을 모두 보존한다. */
    private Map<String, List<SubwayLineMaster>> indexLinesById(
            Collection<SubwayLineMaster> lines
    ) {
        Map<String, List<SubwayLineMaster>> index = new HashMap<>();
        for (SubwayLineMaster line : validLines(lines)) {
            index.computeIfAbsent(line.subwayId(), ignored -> new ArrayList<>()).add(line);
        }
        index.replaceAll((key, value) -> List.copyOf(value));
        return Map.copyOf(index);
    }

    /** 방향·서비스 패턴·최소 두 역이 모두 있는 topology만 검색 대상으로 사용한다. */
    private List<SubwayLineMaster> validLines(Collection<SubwayLineMaster> lines) {
        return lines.stream()
                .filter(Objects::nonNull)
                .filter(line -> line.subwayId() != null && !line.subwayId().isBlank())
                .filter(line -> line.lineName() != null && !line.lineName().isBlank())
                .filter(line -> line.direction() != null && !line.direction().isBlank())
                .filter(line -> line.servicePatternId() != null && !line.servicePatternId().isBlank())
                .filter(line -> line.stations().size() >= 2)
                .toList();
    }

    /** alias ID가 없을 때만 공식 이름 인덱스로 fallback한다. */
    private LineLookup findLineCandidates(List<String> providerLineNames) {
        List<String> normalizedNames = providerLineNames.stream()
                .map(normalizer::normalizeSubwayLineName)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
        Set<SubwayLineMaster> lines = new LinkedHashSet<>();
        int unresolved = 0;
        for (String name : normalizedNames) {
            Set<String> canonicalIds = lineAliasIds.get(name);
            List<SubwayLineMaster> matches = canonicalIds == null
                    ? linesByNormalizedName.getOrDefault(name, List.of())
                    : canonicalIds.stream()
                    .flatMap(id -> linesById.getOrDefault(id, List.of()).stream())
                    .distinct()
                    .toList();
            if (matches.isEmpty()) {
                unresolved++;
            } else {
                lines.addAll(matches);
            }
        }
        long distinctLineIds = lines.stream().map(SubwayLineMaster::subwayId).distinct().count();
        return new LineLookup(normalizedNames, List.copyOf(lines), unresolved, distinctLineIds);
    }

    /** 한 운행 패턴에서 승차역 순번보다 뒤에 있는 하차역 조합만 만든다. */
    private List<Candidate> matchStations(SubwayLineMaster line, SubwayMappingRequest request) {
        List<SubwayStationMaster> stations = traversalStations(line);
        int cycleLength = stations.size() == line.stations().size()
                ? 0 : line.stations().size() - 1;
        List<Candidate> result = new ArrayList<>();
        for (int startIndex = 0; startIndex < (cycleLength > 0 ? cycleLength : stations.size()); startIndex++) {
            if (!matchesStation(request.fromStationName(), stations.get(startIndex))) {
                continue;
            }
            for (int endIndex = startIndex + 1; endIndex < stations.size(); endIndex++) {
                if (cycleLength > 0 && endIndex - startIndex >= cycleLength) {
                    break; // 같은 역으로 한 바퀴 돌아오는 이동이나 여러 바퀴 추정을 금지한다.
                }
                if (!matchesStation(request.toStationName(), stations.get(endIndex))) {
                    continue;
                }
                double passingRatio = passingMatchRatio(
                        request.passingStationNames(), stations.subList(startIndex, endIndex + 1));
                result.add(new Candidate(line, stations, startIndex, endIndex, passingRatio));
            }
        }
        return result;
    }

    /**
     * 공식 패턴 자체가 같은 역으로 닫히는 단순 순환선만 한 번 이어 읽는다.
     * 중간에 같은 역이 반복되는 회차/지선 패턴과 열린 단축 운행에는 적용하지 않는다.
     * 복제한 역의 정적 ID와 원본 순번은 보존하므로 순환 경계에서 순번이 작아질 수 있다.
     * 소비자는 원본 역 순번이 아니라 출력 edge.order를 이용해 이동 순서를 판단한다.
     */
    private List<SubwayStationMaster> traversalStations(SubwayLineMaster line) {
        List<SubwayStationMaster> original = line.stations();
        int size = original.size();
        if (size < 4 || !original.get(0).stationId().equals(original.get(size - 1).stationId())
                || original.subList(0, size - 1).stream().map(SubwayStationMaster::stationId)
                .distinct().count() != size - 1) {
            return original;
        }
        List<SubwayStationMaster> expanded = new ArrayList<>(original);
        expanded.addAll(original.subList(1, size - 1));
        return List.copyOf(expanded);
    }

    /** 별칭이 있으면 정적 stationId만 비교하고, 없을 때만 기본 정규화 이름을 비교한다. */
    private boolean matchesStation(String providerName, SubwayStationMaster officialStation) {
        String normalizedProvider = normalizer.normalizeSubwayStationName(providerName);
        Set<String> aliasIds = stationAliasIds.get(normalizedProvider);
        if (aliasIds != null) {
            return aliasIds.contains(officialStation.stationId());
        }
        return normalizer.normalizeSubwayStationName(officialStation.stationName())
                .equals(normalizedProvider);
    }

    /** 카카오 역 목록이 공식 부분경로와 같은 순서인지 부분수열 비율로 기록한다. */
    private double passingMatchRatio(
            List<String> providerNames,
            List<SubwayStationMaster> officialStations
    ) {
        List<String> usableProviderNames = providerNames.stream()
                .filter(name -> name != null
                        && !normalizer.normalizeSubwayStationName(name).isEmpty())
                .toList();
        if (usableProviderNames.isEmpty()) {
            return 0.0;
        }
        int matched = 0;
        int officialIndex = 0;
        for (String providerName : usableProviderNames) {
            while (officialIndex < officialStations.size()
                    && !matchesStation(providerName, officialStations.get(officialIndex))) {
                officialIndex++;
            }
            if (officialIndex < officialStations.size()) {
                matched++;
                officialIndex++;
            }
        }
        return (double) matched / usableProviderNames.size();
    }

    /** 정적 ID로 segment topology를 만들고 실시간 ID는 각 edge의 별도 crosswalk 필드에 보존한다. */
    private SubwayMappingResult exact(Candidate candidate, int candidateCount) {
        SubwayLineMaster line = candidate.line();
        List<SubwayStationMaster> stations = candidate.stations();
        SubwayStationMaster start = stations.get(candidate.startIndex());
        SubwayStationMaster end = stations.get(candidate.endIndex());
        List<MappedSubwaySegment> segments = new ArrayList<>();
        for (int index = candidate.startIndex(); index < candidate.endIndex(); index++) {
            SubwayStationMaster from = stations.get(index);
            SubwayStationMaster to = stations.get(index + 1);
            segments.add(new MappedSubwaySegment(
                    segments.size() + 1,
                    line.subwayId(),
                    line.realtimeSubwayId(),
                    line.lineName(),
                    line.direction(),
                    from.stationId(),
                    to.stationId(),
                    from.realtimeStationId(),
                    to.realtimeStationId(),
                    from.sequence(),
                    to.sequence()));
        }
        return new SubwayMappingResult(
                SubwayMappingStatus.EXACT,
                line.subwayId(), line.lineName(), start.stationId(), end.stationId(),
                start.sequence(), end.sequence(), line.direction(),
                1.0, round(candidate.passingRatio()), candidateCount, "NONE", segments);
    }

    /** PARTIAL 진단에서도 서로 다른 정적 호선을 임의로 하나 선택하지 않는다. */
    private SubwayMappingResult partialLineOnly(
            List<SubwayLineMaster> lines,
            String reason,
            int candidateCount
    ) {
        SubwayLineMaster representative = lines.get(0);
        boolean sameLine = lines.stream().allMatch(line ->
                Objects.equals(line.subwayId(), representative.subwayId()));
        return new SubwayMappingResult(
                SubwayMappingStatus.PARTIAL,
                sameLine ? representative.subwayId() : null,
                sameLine ? representative.lineName() : null,
                null, null, null, null, null,
                0.0, 0.0, candidateCount, reason, List.of());
    }

    /** 호선조차 확정할 수 없는 결과에는 임의의 대표 ID를 넣지 않는다. */
    private SubwayMappingResult failed(
            SubwayMappingStatus status,
            String reason,
            int candidateCount
    ) {
        return new SubwayMappingResult(
                status, null, null, null, null, null, null, null,
                0.0, 0.0, candidateCount, reason, List.of());
    }

    /**
     * 환승역은 같은 카카오 표기가 호선별 정적 stationId 여러 개를 가리킬 수 있다.
     * 모든 검증 ID를 보존한 뒤 현재 호선 패턴에 포함된 역만 비교해 정상 환승역을 누락하지 않는다.
     */
    private Map<String, Set<String>> normalizedAliasIds(
            Map<String, Set<String>> aliases,
            boolean line
    ) {
        if (aliases == null || aliases.isEmpty()) {
            return Map.of();
        }
        Map<String, Set<String>> result = new HashMap<>();
        aliases.forEach((alias, canonicalIds) -> {
            String key = line
                    ? normalizer.normalizeSubwayLineName(alias)
                    : normalizer.normalizeSubwayStationName(alias);
            if (key.isEmpty() || canonicalIds == null || canonicalIds.isEmpty()
                    || canonicalIds.stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("지하철 별칭 키와 canonical ID는 비어 있을 수 없습니다.");
            }
            result.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(canonicalIds);
        });
        result.replaceAll((key, value) -> Set.copyOf(value));
        return Map.copyOf(result);
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    /** vehicles 전체의 호선 후보와 마스터 미조회 대안 수를 함께 가진 중간 결과다. */
    private record LineLookup(
            List<String> normalizedProviderNames,
            List<SubwayLineMaster> lines,
            int unresolvedProviderCount,
            long distinctLineIds
    ) { }

    /** 실제 출력 key인 정적 subwayId·direction·정적 station 경로 전체를 identity로 사용한다. */
    /** 한 방향·서비스 패턴에서 가능한 승차~하차 부분경로와 통과역 검증 비율이다. */
    private record Candidate(
            SubwayLineMaster line,
            List<SubwayStationMaster> stations,
            int startIndex,
            int endIndex,
            double passingRatio
    ) {
        private String identity() {
            String path = stations.subList(startIndex, endIndex + 1).stream()
                    .map(SubwayStationMaster::stationId)
                    .reduce((left, right) -> left + '>' + right)
                    .orElse("");
            return line.subwayId() + ':' + line.direction() + ':' + path;
        }
    }
}
