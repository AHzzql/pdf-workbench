package cn.edu.pdfworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class TaskNativeFixture {
  static final ObjectMapper JSON=new ObjectMapper();
  static final AtomicBoolean HOLD=new AtomicBoolean();
  static final AtomicInteger CHATS=new AtomicInteger(), ACTIVE=new AtomicInteger();
  static void json(HttpExchange exchange,Object value) throws IOException {
    var bytes=JSON.writeValueAsBytes(value); exchange.getResponseHeaders().set("Content-Type","application/json");
    exchange.sendResponseHeaders(200,bytes.length);
    try(var output=exchange.getResponseBody()){output.write(bytes);}
  }
  static void chunk(OutputStream output,String content,boolean end) throws IOException {
    var choice=new LinkedHashMap<String,Object>();
    choice.put("index",0);choice.put("delta",end?Map.of():Map.of("role","assistant","content",content));
    if(end)choice.put("finish_reason","stop");
    var body=Map.of("id","fixture","object","chat.completion.chunk","created",1,"model","fixture","choices",List.of(choice));
    output.write(("data: "+JSON.writeValueAsString(body)+"\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();
  }
  public static void main(String[] args) throws Exception {
    if(args.length!=2) throw new IllegalArgumentException("Fixture mode and target required");
    if(args[0].equals("cleanup")) {
      if(!args[1].matches("pdf-workbench\\.test\\.[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid test namespace");
      var connection=new org.springframework.amqp.rabbit.connection.CachingConnectionFactory("127.0.0.1",5672);
      connection.setUsername("workbench");connection.setPassword(System.getenv("RABBITMQ_PASSWORD"));connection.setVirtualHost("workbench");
      try {var admin=new org.springframework.amqp.rabbit.core.RabbitAdmin(connection);
        for(String route:List.of("reading","index","cleanup","dead"))admin.deleteQueue(args[1]+"."+route);
        admin.deleteExchange(args[1]);
      } finally {connection.destroy();}
      return;
    }
    if(!args[0].equals("model"))throw new IllegalArgumentException("Invalid fixture mode");
    startModel(Integer.parseInt(args[1]));
  }
  public static HttpServer startModel(int port) throws IOException {
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/control",exchange->{
      if(exchange.getRequestMethod().equals("POST"))HOLD.set(JSON.readTree(exchange.getRequestBody()).path("hold").asBoolean());
      json(exchange,Map.of("held",HOLD.get(),"chatCalls",CHATS.get(),"activeChats",ACTIVE.get()));
    });
    server.createContext("/v1/embeddings",exchange->{
      var input=JSON.readTree(exchange.getRequestBody()).path("input");
      int count=input.isArray()?input.size():1;
      var data=new ArrayList<Object>();
      for(int i=0;i<count;i++){var vector=new float[1024];vector[0]=1;data.add(Map.of("object","embedding","index",i,"embedding",vector));}
      json(exchange,Map.of("object","list","data",data,"model","fixture","usage",Map.of("prompt_tokens",1,"total_tokens",1)));
    });
    server.createContext("/rerank",exchange->{
      int count=JSON.readTree(exchange.getRequestBody()).path("input").path("documents").size();
      var results=new ArrayList<Object>();for(int i=0;i<Math.min(8,count);i++)results.add(Map.of("index",i,"relevance_score",1.0));
      json(exchange,Map.of("output",Map.of("results",results)));
    });
    server.createContext("/v1/chat/completions",exchange->{
      CHATS.incrementAndGet();ACTIVE.incrementAndGet();
      try {
        var request=JSON.readTree(exchange.getRequestBody());
        boolean finalAnswer=request.path("messages").path(0).path("content").asText().startsWith("你是 PDF 阅读助手");
        exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
        try(var output=exchange.getResponseBody()){
          // The SDK keeps one chunk of lookahead for usage metadata.
          chunk(output,"Alpha ",false);chunk(output,"is 42. ",false);
          while(HOLD.get() && finalAnswer){
            output.write(": fixture-heartbeat\n\n".getBytes(StandardCharsets.UTF_8));output.flush();
            try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
          }
          chunk(output,"[p1-0]",false);chunk(output,"",true);
          output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));output.flush();
        }
      } catch(IOException e){exchange.close();}
      finally{ACTIVE.decrementAndGet();}
    });
    server.start();return server;
  }
}
