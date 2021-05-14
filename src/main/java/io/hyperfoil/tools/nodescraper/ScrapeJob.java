package io.hyperfoil.tools.nodescraper;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonGenerator;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.parsetools.RecordParser;

public class ScrapeJob implements Handler<Long> {
   private static final Logger log = Logger.getLogger(ScrapeJob.class);
   private static final byte[] NODE_CPU_SECONDS_TOTAL = bytes("node_cpu_seconds_total");
   private static final byte[] MODE = bytes("mode=\"");

   private static byte[] bytes(String node_cpu_seconds_total) {
      try {
         return node_cpu_seconds_total.getBytes(StandardCharsets.UTF_8.name());
      } catch (UnsupportedEncodingException e) {
         throw new IllegalStateException(e);
      }
   }

   final Scraper scraper;
   final Config config;
   final File file;
   final JsonGenerator generator;
   long timerId;
   Map<String, Map<String, Double>> lastCpuSeconds = new HashMap<>();

   public ScrapeJob(Scraper scraper, Config config, File file, JsonGenerator generator) throws IOException {
      this.scraper = scraper;
      this.config = config;
      this.file = file;
      this.generator = generator;
      generator.writeStartArray();
      file.deleteOnExit();
      log.infof("Started file %s", file);
   }

   @Override
   public void handle(Long periodicTimerId) {
      List<Future> futures = new ArrayList<>();
      for (Config.NodeConfig nodeConfig : config.nodes) {
         URL parsed;
         try {
            parsed = new URL(nodeConfig.url);
         } catch (MalformedURLException e) {
            log.errorf(e, "Cannot parse url %s", nodeConfig);
            continue;
         }
         int port = parsed.getPort() < 0 ? parsed.getDefaultPort() : parsed.getPort();
         log.tracef("Scraping %s:%s, %s using token %s", parsed.getHost(), port, parsed.getFile(), scraper.authToken);
         Promise<HashMap<String, Double>> promise = Promise.promise();
         futures.add(promise.future());
         RequestOptions requestOptions = new RequestOptions().setSsl("https".equalsIgnoreCase(parsed.getProtocol()))
               .setHost(parsed.getHost()).setPort(port).setURI(parsed.getFile());
         HttpClientRequest request = scraper.httpClient.get(requestOptions);
         if (scraper.authToken != null && !scraper.authToken.isEmpty()) {
            request.putHeader(HttpHeaders.AUTHORIZATION, "Bearer " + scraper.authToken);
         }
         request.handler(response -> {
            if (response.statusCode() != 200) {
               log.errorf("Unexpected response status: %d %s", response.statusCode(), response.statusMessage());
               promise.fail("Unexpected response status");
               return;
            }
            processBody(response, promise);
         }).exceptionHandler(error -> {
            log.errorf(error, "Request failed");
            promise.fail("Request failed");
         }).end();
      }
      CompositeFuture.all(futures).onComplete(result -> {
         if (result.failed()) {
            log.errorf(result.cause(), "Cannot write results");
            return;
         }

         try {
            generator.writeStartObject();
            generator.writeNumberField("timestamp", System.currentTimeMillis());
            generator.writeFieldName("cpuinfo");
            generator.writeStartArray();
            int size = result.result().size();
            for (int i = 0; i < size; ++i) {
               String node = config.nodes.get(i).node;
               Map<String, Double> map = result.result().resultAt(i);
               Map<String, Double> prev = lastCpuSeconds.put(node, map);
               if (prev != null) {
                  generator.writeStartObject();
                  generator.writeStringField("node", node);
                  for (Map.Entry<String, Double> entry : prev.entrySet()) {
                     Double value = map.get(entry.getKey());
                     if (value != null) {
                        generator.writeFieldName(entry.getKey());
                        generator.writeRawValue(BigDecimal.valueOf(1000 * (value - entry.getValue()) / config.scrapeInterval).setScale(3, RoundingMode.HALF_UP).toPlainString());
                     }
                  }
                  generator.writeEndObject();
               }
            }
            generator.writeEndArray();
            generator.writeEndObject();
         } catch (IOException e) {
            log.errorf(e, "Failed to write record");
         }
      });
   }

   private void processBody(HttpClientResponse response, Promise<HashMap<String, Double>> promise) {
      HashMap<String, Double> cpuSecondsByMode = new HashMap<>();
      RecordParser parser = RecordParser.newDelimited("\n");
      parser.setOutput(line -> {
         if (matches(line, NODE_CPU_SECONDS_TOTAL)) {
            int modeIndex = indexOf(line, MODE, NODE_CPU_SECONDS_TOTAL.length);
            int modeEnd = indexOf(line, (byte) '"', modeIndex + MODE.length);
            if (modeIndex < 0 || modeEnd < 0) {
               return;
            }
            String mode = new String(line.getBytes(modeIndex + MODE.length, modeEnd));
            int spaceIndex = indexOf(line, (byte) ' ', modeEnd + 1);
            if (spaceIndex < 0) {
               return;
            }
            double value = Double.parseDouble(new String(line.getBytes(spaceIndex + 1, line.length())));
            Double prev = cpuSecondsByMode.putIfAbsent(mode, value);
            if (prev != null ){
               cpuSecondsByMode.put(mode, value + prev);
            }
         }
      });
      response.handler(parser).endHandler(nil -> promise.complete(cpuSecondsByMode));
   }

   private int indexOf(Buffer line, byte[] string, int searchFrom) {
      OUTER: for (int i = searchFrom; i < line.length(); ++i) {
         for (int j = 0; j < string.length; ++j) {
            if (line.getByte(i + j) != string[j]) continue OUTER;;
         }
         return i;
      }
      return -1;
   }

   private int indexOf(Buffer line, byte b, int searchForm) {
      for (int i = searchForm; i < line.length(); ++i) {
         if (line.getByte(i) == b) {
            return i;
         }
      }
      return -1;
   }

   private boolean matches(Buffer line, byte[] bytes) {
      if (line.length() < bytes.length) {
         return false;
      }
      for (int i = 0; i < bytes.length; ++i) {
         if (line.getByte(i) != bytes[i]) {
            return false;
         }
      }
      return true;
   }

   public void stop(Long cancelTimerId) {
      try {
         generator.writeEndArray();
         generator.close();
      } catch (IOException e) {
         log.error("Failed to complete the JSON file.", e);
      }
      scraper.vertx.cancelTimer(timerId);
      // schedule file deletion in 1 minute
      scraper.vertx.setTimer(60000, id -> {
         file.delete();
      });
   }
}
