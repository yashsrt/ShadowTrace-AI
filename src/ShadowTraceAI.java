import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** ShadowTrace AI: Java 17+, standard library only. Educational incident investigation platform. */
public class ShadowTraceAI {
    static final String[] NAMES={"Database","PaymentAPI","Checkout"};
    static final Map<String,List<String>> DEPENDENTS=Map.of(
        "Database",List.of("PaymentAPI","Checkout"),"PaymentAPI",List.of("Checkout"),"Checkout",List.of());
    static final Map<String,double[]> BASE=Map.of("Database",new double[]{90,0.4,32,99.98},
        "PaymentAPI",new double[]{110,0.6,36,99.97},"Checkout",new double[]{120,0.5,30,99.98});
    static final double[] SCALE={25,0.35,12,0.06};
    static final int K=7;
    static final Random RNG=new Random(42);
    static final Object LOCK=new Object();
    static final Map<String,Model> MODELS=new LinkedHashMap<>();
    static final Map<String,Reading> LATEST=new LinkedHashMap<>();
    static final Map<String,Deque<Reading>> HISTORY=new LinkedHashMap<>();
    static final Deque<Incident> INCIDENTS=new ArrayDeque<>();
    static final AtomicLong SEQ=new AtomicLong();
    static final Path LOG=Paths.get("data","metrics.csv");
    static final Path INCIDENT_LOG=Paths.get("data","incidents.csv");
    static volatile boolean incidentMode=false;
    static volatile boolean autoGenerate=true;
    static long tick=0;

    static class Reading {
        final long id, time; final String service,source; final double latency,errors,cpu,integrity,score,ratio;
        final String severity,reason;
        Reading(long id,long time,String service,String source,double l,double e,double c,double i,Model.Result r){
            this.id=id;this.time=time;this.service=service;this.source=source;
            latency=l;errors=e;cpu=c;integrity=i;score=r.score;ratio=r.ratio;
            severity=ratio>=2.0?"CRITICAL":ratio>=1.0?"WARNING":"NORMAL";reason=r.reason;
        }
    }
    static class Incident {
        final long time;final String root,explanation;final double confidence;
        Incident(long t,String r,double c,String e){time=t;root=r;confidence=c;explanation=e;}
    }
    /** kNN novelty detection trained on simulated healthy telemetry. No hardcoded anomaly scores. */
    static class Model {
        final double[] mean=new double[4],sd=new double[4];
        final List<double[]> train=new ArrayList<>();
        final double threshold;
        static class Result {double score,ratio;String reason;Result(double s,double r,String why){score=s;ratio=r;reason=why;}}
        Model(String service){
            double[] b=BASE.get(service);
            for(int i=0;i<4;i++){mean[i]=b[i];sd[i]=SCALE[i];}
            Random r=new Random(1000+service.hashCode());
            for(int j=0;j<500;j++){
                double common=r.nextGaussian()*0.3;
                double[] v=new double[4];
                for(int i=0;i<4;i++) v[i]=common+(r.nextGaussian()*0.65);
                train.add(v);
            }
            List<Double> validation=new ArrayList<>();
            for(int j=0;j<250;j++){
                double common=r.nextGaussian()*0.3;
                double[] v=new double[4];
                for(int i=0;i<4;i++)v[i]=common+r.nextGaussian()*0.65;
                validation.add(knn(v));
            }
            Collections.sort(validation);
            threshold=Math.max(0.0001,validation.get((int)(validation.size()*0.99)-1));
        }
        double[] normalize(double[] v){double[] a=new double[4];for(int i=0;i<4;i++)a[i]=(v[i]-mean[i])/sd[i];return a;}
        double knn(double[] v){
            PriorityQueue<Double> maxHeap=new PriorityQueue<>(Comparator.reverseOrder());
            for(double[] point:train){double d=0;for(int i=0;i<4;i++){double diff=v[i]-point[i];d+=diff*diff;}d=Math.sqrt(d);
                if(maxHeap.size()<K)maxHeap.offer(d);else if(d<maxHeap.peek()){maxHeap.poll();maxHeap.offer(d);}}
            double sum=0;for(double d:maxHeap)sum+=d;return sum/K;
        }
        Result predict(double[] raw){
            double[] z=normalize(raw);double score=knn(z),ratio=score/threshold;
            String[] labels={"latency","error rate","CPU usage","data integrity"};
            int worst=0;double largest=0;
            for(int i=0;i<4;i++){double directional=i==3?-z[i]:z[i];if(directional>largest){largest=directional;worst=i;}}
            String reason=largest<0.5?"unusual metric combination":labels[worst]+" ("+String.format(Locale.US,"%.1f",largest)+"σ adverse deviation)";
            return new Result(score,ratio,reason);
        }
    }
    static void setup() throws IOException {
        Files.createDirectories(LOG.getParent());
        if(!Files.exists(LOG))Files.writeString(LOG,"timestamp,service,source,latency_ms,error_pct,cpu_pct,integrity_pct,knn_score,threshold_ratio,severity\n");
        if(!Files.exists(INCIDENT_LOG))Files.writeString(INCIDENT_LOG,"timestamp,likely_root,relative_evidence_score,explanation\n");
        for(String n:NAMES){MODELS.put(n,new Model(n));HISTORY.put(n,new ArrayDeque<>());}
    }
    static Reading ingest(String service,double l,double e,double cpu,double integrity,String source) throws IOException {
        if(!MODELS.containsKey(service))throw new IllegalArgumentException("Unknown service: "+service);
        double[] vals={l,e,cpu,integrity};for(double v:vals)if(!Double.isFinite(v))throw new IllegalArgumentException("Values must be finite");
        if(l<0||e<0||e>100||cpu<0||cpu>100||integrity<0||integrity>100)throw new IllegalArgumentException("Invalid metric range");
        synchronized(LOCK){
            Reading rd=new Reading(SEQ.incrementAndGet(),System.currentTimeMillis(),service,source,l,e,cpu,integrity,MODELS.get(service).predict(vals));
            LATEST.put(service,rd);Deque<Reading> q=HISTORY.get(service);q.addLast(rd);if(q.size()>80)q.removeFirst();
            Files.writeString(LOG,String.format(Locale.US,"%s,%s,%s,%.4f,%.4f,%.4f,%.4f,%.6f,%.6f,%s%n",Instant.ofEpochMilli(rd.time),service,source,l,e,cpu,integrity,rd.score,rd.ratio,rd.severity),StandardOpenOption.APPEND);
            return rd;
        }
    }
    static double rootPriority(String service){
        Reading r=LATEST.get(service);if(r==null||r.ratio<1)return 0;
        double priority=Math.min(12,r.ratio);
        for(String downstream:DEPENDENTS.get(service)){
            Reading d=LATEST.get(downstream);
            if(d!=null&&d.ratio>=1&&Math.abs(r.time-d.time)<20000)priority+=2.5;
        }
        return priority;
    }
    static Incident findRoot(){
        String root=null;double best=0,second=0;
        for(String n:NAMES){double p=rootPriority(n);if(p>best){second=best;best=p;root=n;}else if(p>second)second=p;}
        if(root==null)return null;
        int count=0;for(String dep:DEPENDENTS.get(root)){Reading r=LATEST.get(dep);if(r!=null&&r.ratio>=1)count++;}
        double relative=best/(best+second+1e-9);
        String explanation="Ranked using anomaly severity and "+count+" anomalous downstream service(s). Correlation is not causation.";
        return new Incident(System.currentTimeMillis(),root,relative,explanation);
    }
    static void simulate() {
        try{
            long current;
            synchronized(LOCK){current=++tick;}
            boolean bad=incidentMode;
            for(String n:NAMES){
                double[] b=BASE.get(n);double[] v=new double[4];
                for(int i=0;i<4;i++)v[i]=b[i]+RNG.nextGaussian()*SCALE[i]*0.65;
                if(bad){
                    double multiplier=n.equals("Database")?1.0:n.equals("PaymentAPI")?0.55:0.4;
                    v[0]+=230*multiplier;v[1]+=3.8*multiplier;v[2]+=43*multiplier;v[3]-=0.42*multiplier;
                }
                v[0]=Math.max(0,v[0]);v[1]=Math.max(0,Math.min(100,v[1]));v[2]=Math.max(0,Math.min(100,v[2]));v[3]=Math.max(0,Math.min(100,v[3]));
                ingest(n,v[0],v[1],v[2],v[3],"simulated");
            }
            synchronized(LOCK){Incident incident=findRoot();if(incident!=null&&(INCIDENTS.isEmpty()||System.currentTimeMillis()-INCIDENTS.peekFirst().time>12000)){
                INCIDENTS.addFirst(incident);while(INCIDENTS.size()>30)INCIDENTS.removeLast();
                Files.writeString(INCIDENT_LOG,String.format(Locale.US,"%s,%s,%.3f,%s%n",Instant.ofEpochMilli(incident.time),incident.root,incident.confidence,incident.explanation),StandardOpenOption.APPEND);
                System.out.println("[ALERT] Likely root: "+incident.root+" | "+incident.explanation);
            }}
        }catch(Exception ex){System.err.println("Simulator: "+ex.getMessage());}
    }
    static String escape(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");}
    static String quote(String s){return "\""+escape(s)+"\"";}
    static String readingJson(Reading r){return "{\"time\":"+r.time+",\"service\":"+quote(r.service)+",\"source\":"+quote(r.source)+
        ",\"latency\":"+r.latency+",\"errors\":"+r.errors+",\"cpu\":"+r.cpu+",\"integrity\":"+r.integrity+
        ",\"score\":"+r.score+",\"ratio\":"+r.ratio+",\"severity\":"+quote(r.severity)+",\"reason\":"+quote(r.reason)+"}";}
    static String snapshot(){
        synchronized(LOCK){StringBuilder sb=new StringBuilder("{\"incidentMode\":"+incidentMode+",\"autoGenerate\":"+autoGenerate+",\"services\":[");
            boolean first=true;for(String n:NAMES){Reading r=LATEST.get(n);if(r!=null){if(!first)sb.append(',');first=false;sb.append(readingJson(r));}}sb.append("],\"history\":{");
            first=true;for(String n:NAMES){if(!first)sb.append(',');first=false;sb.append(quote(n)).append(": [");boolean inner=true;for(Reading r:HISTORY.get(n)){if(!inner)sb.append(',');inner=false;sb.append(readingJson(r));}sb.append(']');}
            sb.append("},\"root\":");Incident root=findRoot();sb.append(root==null?"null":"{\"service\":"+quote(root.root)+",\"relativeEvidence\":"+root.confidence+",\"explanation\":"+quote(root.explanation)+"}");
            sb.append(",\"incidents\":[");first=true;for(Incident i:INCIDENTS){if(!first)sb.append(',');first=false;sb.append("{\"time\":"+i.time+",\"root\":"+quote(i.root)+",\"relativeEvidence\":"+i.confidence+"}");}sb.append("]}");return sb.toString();
        }
    }
    static void reply(HttpExchange x,int code,String type,String body)throws IOException{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");x.getResponseHeaders().set("Access-Control-Allow-Origin","*");
        x.sendResponseHeaders(code,bytes.length);try(OutputStream o=x.getResponseBody()){o.write(bytes);}
    }
    static void route(HttpExchange x)throws IOException{
        try{
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(method.equals("OPTIONS")){x.getResponseHeaders().set("Access-Control-Allow-Origin","*");x.getResponseHeaders().set("Access-Control-Allow-Methods","GET,POST,OPTIONS");x.getResponseHeaders().set("Access-Control-Allow-Headers","Content-Type");x.sendResponseHeaders(204,-1);return;}
            if(path.equals("/")&&method.equals("GET")){reply(x,200,"text/html",Files.readString(Paths.get("web","index.html")));return;}
            if(path.equals("/api/status")&&method.equals("GET")){reply(x,200,"application/json",snapshot());return;}
            if(path.equals("/api/demo/incident")&&method.equals("POST")){incidentMode=true;simulate();reply(x,200,"application/json","{\"message\":\"Incident simulation enabled\"}");return;}
            if(path.equals("/api/demo/healthy")&&method.equals("POST")){incidentMode=false;simulate();reply(x,200,"application/json","{\"message\":\"Healthy simulation enabled\"}");return;}
            if(path.equals("/api/demo/pause")&&method.equals("POST")){autoGenerate=!autoGenerate;reply(x,200,"application/json","{\"autoGenerate\":"+autoGenerate+"}");return;}
            if(path.equals("/api/metrics")&&method.equals("POST")){
                String input=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                if(input.length()>2000)throw new IllegalArgumentException("Request too large");
                // Accept a simple JSON object with scalar keys; no third-party JSON dependency.
                Map<String,String> values=new HashMap<>();
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\"(service|latency|errors|cpu|integrity)\\\"\\s*:\\s*(\\\"[^\\\"]*\\\"|-?\\d+(?:\\.\\d+)?)").matcher(input);
                while(m.find())values.put(m.group(1),m.group(2).replace("\"",""));
                String service=values.get("service");if(service==null)throw new IllegalArgumentException("Missing service");
                Reading r=ingest(service,Double.parseDouble(values.get("latency")),Double.parseDouble(values.get("errors")),Double.parseDouble(values.get("cpu")),Double.parseDouble(values.get("integrity")),"external");
                reply(x,200,"application/json",readingJson(r));return;
            }
            reply(x,404,"application/json","{\"error\":\"Not found\"}");
        }catch(IllegalArgumentException|NullPointerException e){reply(x,400,"application/json","{\"error\":"+quote(e.getMessage()==null?"Missing field":e.getMessage())+"}");}
        catch(Exception e){e.printStackTrace();reply(x,500,"application/json","{\"error\":\"Internal error\"}");}
    }
    public static void main(String[] args)throws Exception{
        setup();int port=args.length>0?Integer.parseInt(args[0]):8080;
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",ShadowTraceAI::route);server.setExecutor(Executors.newFixedThreadPool(6));server.start();
        ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();scheduler.scheduleAtFixedRate(()->{if(autoGenerate)simulate();},0,2,TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{scheduler.shutdownNow();server.stop(0);}));
        System.out.println("ShadowTrace AI running at http://localhost:"+port);
        System.out.println("Press Ctrl+C to stop. Data saved in data/*.csv");
    }
}
