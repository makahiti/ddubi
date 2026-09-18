package com.ssafy.ddubi.domain.route.model;

import java.util.*;

/** provider 순서를 보존하여 WALK의 역할만 붙인다. 공용 segment ID나 새 이동시간을 만들지 않는다. */
public final class RouteLegRoleClassifier {
    public enum Role { ACCESS_WALK, TRANSFER_WALK, EGRESS_WALK, RIDE }
    public Map<Integer,Role> classify(List<RouteSegment> steps) {
        var result=new LinkedHashMap<Integer,Role>();
        int first=-1,last=-1;
        for (int i=0;i<steps.size();i++) if (steps.get(i).mode()!=TransportMode.WALK) { if(first<0) first=i; last=i; }
        for (int i=0;i<steps.size();i++) result.put(steps.get(i).sequence(),steps.get(i).mode()!=TransportMode.WALK
                ? Role.RIDE : first<0 || i<first ? Role.ACCESS_WALK : i>last ? Role.EGRESS_WALK : Role.TRANSFER_WALK);
        return Collections.unmodifiableMap(result);
    }
}
