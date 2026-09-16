import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Imports the validated deployed snapshot into the existing schema; dry-run rolls back. */
public class SubwayImportTool {
    static Object get(Object value, String method) throws Exception {
        return value.getClass().getMethod(method).invoke(value);
    }
    static void insert(Connection c, String sql, Object... values) throws Exception {
        try (var p = c.prepareStatement(sql)) {
            for (int i=0;i<values.length;i++) p.setObject(i+1, values[i]);
            p.executeUpdate();
        }
    }
    public static void main(String[] args) throws Exception {
        String file="/opt/ddubi/transit-master/subway/metropolitan-subway-master.json.gz";
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(file))));
        if (!sha.equals("f90444ef16df7a1658388d4cdd3946776df9df759cbf760e36369a5057fd7433")) throw new IllegalStateException("Unexpected artifact SHA");
        Class<?> providerType=Class.forName("com.ssafy.ddubi.domain.route.mapping.ResourceSubwayMasterDataProvider");
        Class<?> jdbcProviderType=Class.forName("com.ssafy.ddubi.domain.route.mapping.JdbcSeoulTransitMasterDataProvider");
        Class<?> mapperType=Class.forName("com.fasterxml.jackson.databind.ObjectMapper");
        Object provider=providerType.getConstructors()[0].newInstance(null,mapperType.getConstructor().newInstance(),
                Class.forName("org.springframework.core.io.DefaultResourceLoader").getConstructor().newInstance(),"file:"+file);
        String version=((Optional<?>)get(provider,"activeVersion")).orElseThrow().toString();
        var lines=(List<?>)providerType.getMethod("loadSubwayLines",String.class).invoke(provider,version);
        Object aliases=providerType.getMethod("loadAliases",String.class).invoke(provider,version);
        Map<String,Object> stations=new LinkedHashMap<>();
        Map<String,String> lineNames=new LinkedHashMap<>(), stationNames=new LinkedHashMap<>();
        for(Object line:lines) {
            String id=get(line,"subwayId").toString(), name=get(line,"lineName").toString();
            if(lineNames.putIfAbsent(id,name)!=null && !lineNames.get(id).equals(name)) throw new IllegalStateException("Conflicting line name");
            for(Object station:(List<?>)get(line,"stations")) {
                String sid=get(station,"stationId").toString();
                Object old=stations.putIfAbsent(sid,station);
                if(old!=null && !Objects.equals(get(old,"stationName"),get(station,"stationName"))) throw new IllegalStateException("Conflicting station name id="+sid);
                stationNames.put(sid,get(station,"stationName").toString());
            }
        }
        String url="jdbc:mysql://"+env("MYSQL_HOST","mysql")+":"+env("MYSQL_PORT","3306")+"/"+env("MYSQL_DATABASE","ddubi")+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul";
        try(Connection c=DriverManager.getConnection(url,env("MYSQL_USER","ddubi"),System.getenv("MYSQL_PASSWORD"))) {
            c.setAutoCommit(false);
            try {
                // Lock the existing version rows while publishing a new version.
                boolean scaffold=false;
                try(var p=c.prepareStatement("SELECT data_version,status,source_name FROM seoul_transit_master_version FOR UPDATE");var r=p.executeQuery()) {
                    while(r.next()) if(version.equals(r.getString(1))) {
                        if(!"STAGING".equals(r.getString(2)) || !("resource-sha256:"+sha).equals(r.getString(3))) throw new IllegalStateException("Version already exists; refusing overwrite");
                        scaffold=true;
                    }
                }
                if(scaffold) {
                    // Resource mapping cache creates this exact SHA-bound staging row before DB master import.
                    for(String table:List.of("seoul_subway_line","seoul_subway_station","seoul_subway_line_station","transit_alias")) {
                        try(var p=c.prepareStatement("SELECT COUNT(*) FROM "+table+" WHERE data_version=?")) {
                            p.setString(1,version);
                            try(var r=p.executeQuery()) {r.next();if(r.getLong(1)!=0)throw new IllegalStateException("Existing master rows; refusing overwrite");}
                        }
                    }
                } else insert(c,"INSERT INTO seoul_transit_master_version(data_version,status,source_name) VALUES(?,'STAGING',?)",version,"metropolitan-subway-master.json.gz sha256="+sha);
                for(Object line:lines) insert(c,"INSERT INTO seoul_subway_line(data_version,subway_id,realtime_subway_id,direction,service_pattern_id,line_name) VALUES(?,?,?,?,?,?)",version,get(line,"subwayId"),get(line,"realtimeSubwayId"),get(line,"direction"),get(line,"servicePatternId"),get(line,"lineName"));
                // 역 자체에는 특정 호선의 ID를 대표값으로 잘못 붙이지 않는다.
                for(Object station:stations.values()) insert(c,"INSERT INTO seoul_subway_station(data_version,station_id,station_name) VALUES(?,?,?)",version,get(station,"stationId"),get(station,"stationName"));
                int rows=0;
                for(Object line:lines) for(Object station:(List<?>)get(line,"stations")) {
                    insert(c,"INSERT INTO seoul_subway_line_station(data_version,subway_id,direction,service_pattern_id,station_id,station_sequence,realtime_station_id) VALUES(?,?,?,?,?,?,?)",version,get(line,"subwayId"),get(line,"direction"),get(line,"servicePatternId"),get(station,"stationId"),get(station,"sequence"),get(station,"realtimeStationId")); rows++;
                }
                int aliasCount=0;
                for(String kind:List.of("LINE","STATION")) {
                    var map=(Map<?,?>)get(aliases,kind.equals("LINE")?"subwayLineAliasIds":"subwayStationAliasIds");
                    for(var entry:map.entrySet()) for(Object id:(Set<?>)entry.getValue()) {
                        insert(c,"INSERT INTO transit_alias(data_version,provider,mode,alias_type,alias_name,normalized_name,canonical_id,canonical_name,verified,active) VALUES(?,'KAKAO','SUBWAY',?,?,?,?,?,TRUE,TRUE)",version,kind,entry.getKey(),entry.getKey(),id,(kind.equals("LINE")?lineNames:stationNames).get(id)); aliasCount++;
                    }
                }
                Object ds=Class.forName("org.springframework.jdbc.datasource.SingleConnectionDataSource").getConstructor(Connection.class,boolean.class).newInstance(c,true);
                Class<?> jdbcType=Class.forName("org.springframework.jdbc.core.JdbcTemplate");
                Object jdbc=jdbcType.getConstructor(DataSource.class).newInstance(ds);
                Object readback=jdbcProviderType.getConstructor(jdbcType).newInstance(jdbc);
                var readLines=(List<?>)jdbcProviderType.getMethod("loadSubwayLines",String.class).invoke(readback,version);
                Object readAliases=jdbcProviderType.getMethod("loadAliases",String.class).invoke(readback,version);
                if(!new HashSet<>(lines).equals(new HashSet<>(readLines)) || !aliases.equals(readAliases)) throw new IllegalStateException("DB round-trip mismatch");
                insert(c,"UPDATE seoul_transit_master_version SET status='RETIRED' WHERE status='ACTIVE'");
                insert(c,"UPDATE seoul_transit_master_version SET status='ACTIVE',activated_at=CURRENT_TIMESTAMP(6) WHERE data_version=?",version);
                insert(c,"INSERT INTO pipeline_config(config_key,config_value) VALUES('seoul_transit_master_active_version',?) ON DUPLICATE KEY UPDATE config_value=VALUES(config_value)",version);
                boolean commit=args.length==1 && args[0].equals("--commit");
                if(commit)c.commit(); else c.rollback();
                System.out.println((commit?"COMMITTED":"DRY_RUN_ROLLED_BACK")+" version="+version+" patterns="+lines.size()+" stations="+stations.size()+" lineStations="+rows+" aliases="+aliasCount+" roundTrip=OK");
            } catch(Exception failure) { c.rollback(); throw failure; }
        }
    }
    static String env(String key,String fallback){String value=System.getenv(key);return value==null||value.isBlank()?fallback:value;}
}
