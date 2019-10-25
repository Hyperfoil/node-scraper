package io.hyperfoil.tools.nodescraper;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class Config {
   public List<NodeConfig> nodes;
   public long scrapeInterval;
   public long jobTimeout = TimeUnit.HOURS.toMillis(2);

   public static class NodeConfig {
      public String node;
      public String url;
   }
}
