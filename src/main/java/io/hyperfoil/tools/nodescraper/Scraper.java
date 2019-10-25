package io.hyperfoil.tools.nodescraper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;

@Path("/")
public class Scraper {
    @ConfigProperty(name = "scrape.dir", defaultValue = "")
    String scrapeDir;

    @ConfigProperty(name = "scrape.auth.token", defaultValue = "")
    String authToken;

    @Inject
    Vertx vertx;
    HttpClient httpClient;
    ConcurrentMap<Long, ScrapeJob> jobs = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        HttpClientOptions options = new HttpClientOptions().setSsl(true).setTrustAll(true).setVerifyHost(false);
        httpClient = vertx.createHttpClient(options);
        if (scrapeDir == null || scrapeDir.isEmpty()) {
            scrapeDir = System.getProperty("java.io.tmpdir");
        }
    }

    @POST
    @Path("start")
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public String start(Config config) throws IOException {
        File file = Files.createTempFile(Paths.get(scrapeDir), "nodescrape-", ".json").toFile();
        JsonGenerator generator = new JsonFactory().setCodec(new ObjectMapper()).createGenerator(file, JsonEncoding.UTF8);
        ScrapeJob job = new ScrapeJob(this, config, file, generator);
        job.timerId = vertx.setPeriodic(config.scrapeInterval, job);
        jobs.put(job.timerId, job);
        job.handle(job.timerId);
        vertx.setTimer(config.jobTimeout, job::stop);
        return String.valueOf(job.timerId);
    }

    @GET
    @Path("/stop/{jobId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response stop(@PathParam("jobId") Long jobId) {
        ScrapeJob job = jobs.get(jobId);
        if (job == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        job.stop(0L);
        return Response.ok(job.file).build();
    }
}