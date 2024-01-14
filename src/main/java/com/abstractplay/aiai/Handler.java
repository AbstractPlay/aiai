package com.abstractplay.aiai;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;

public class Handler implements RequestHandler<SQSEvent, Void>{

	private static final Logger LOG = LogManager.getLogger(Handler.class);
    private static String layerpath = "/opt/java/lib/aiaicli/";

    @Override
    public Void handleRequest(SQSEvent event, Context context)
    {
        for(SQSMessage msg : event.getRecords()) {
            // simple initial log
            LOG.info("received: {}", msg.getBody());

            // process message body
            Map<String,String> myMap = new HashMap<String, String>();
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                myMap = objectMapper.readValue(msg.getBody(), HashMap.class);
                if (! myMap.containsKey("mgl")) {
                    throw new Error("You must provide the name of the MGL file to execute.");
                }
                if (! myMap.containsKey("gameid")) {
                    throw new Error("You must provide the game id.");
                }
                if (! myMap.containsKey("seed")) {
                    myMap.put("seed", "-1");
                }
                if (! myMap.containsKey("ttt")) {
                    myMap.put("ttt", "10");
                }
                if (! myMap.containsKey("history")) {
                    myMap.put("history", "");
                }
                LOG.info("Parsed message body: {}", myMap);
            } catch (JsonProcessingException e) {
                LOG.error("An error occured while processing the message body: {}", e.getMessage());
            }

            // run it!
            if (myMap.get("mgl").equals("TEST")) {
                try {
                    LOG.info(generateCode());
                } catch (CodeGenerationException e) {
                    LOG.error("An error occurred while generating a code: " + e.getMessage());
                    LOG.error(e.getCause());
                }
            } else {
                String[] envp = { "HOME=/tmp" };
                Process proc;
                try {
                    proc = Runtime.getRuntime().exec("java -jar " + layerpath + "aiai.jar AI " + layerpath + "mgl/" + myMap.get("mgl") + ".mgl " + myMap.get("seed") + " " + myMap.get("ttt") + " " + myMap.get("history"), envp, new File("/opt/java/lib/aiaicli"));

                    // Process proc = Runtime.getRuntime().exec("java -version");
                    try {
                        proc.waitFor();
                    } catch (InterruptedException e) {
                        LOG.error("Error invoking AiAi (InterruptedException): {}", e.getMessage());
                    }

                    // Then retreive the process output
                    InputStream in = proc.getInputStream();
                    InputStream err = proc.getErrorStream();

                    byte b[]=new byte[in.available()];
                    in.read(b,0,b.length);
                    LOG.info(new String(b));
                    // System.out.println(new String(b));

                    byte c[]=new byte[err.available()];
                    err.read(c,0,c.length);
                    LOG.info(new String(c));
                    // outputStream.write(c);
                    // System.out.println(new String(c));

                    // extract move
                    int left = new String(b).indexOf("<move>");
                    int right = new String(b).indexOf("</move>");
                    if (left == -1 || right == -1) {
                        throw new Error("Could not extract a move");
                    }
                    String move = new String(b).substring(left + 6, right);
                    LOG.info(move);

                    // submit to endpoint
                    String apiurl = System.getenv("API_ENDPOINT");
                    if (apiurl == null) {
                        throw new Error("Could not find the API_ENDPOINT environment variable.");
                    }
                    Map<String, String> requestParams = new HashMap<>();
                    requestParams.put("query", "bot_move");
                    requestParams.put("uid", "SkQfHAjeDxs8eeEnScuYA");
                    requestParams.put("token", generateCode());
                    requestParams.put("metaGame", myMap.get("mgl"));
                    requestParams.put("gameid", myMap.get("gameid"));
                    requestParams.put("move", move);
                    String encodedURL = requestParams.keySet().stream()
                        .map(key -> key + "=" + encodeValue(requestParams.get(key)))
                        .collect(Collectors.joining("&", apiurl + "?", ""));
                    HttpRequest request = HttpRequest.newBuilder()
                        .uri(new URI(encodedURL))
                        .GET()
                        .build();
                    HttpResponse<String> response = HttpClient.newBuilder()
                        .proxy(ProxySelector.getDefault())
                        .build()
                        .send(request, BodyHandlers.ofString());
                    if (response.statusCode() != 200) {
                        throw new Error("Got an error response from the endpoint:\nStatus code: " + response.statusCode() + "\nContent: " + response.body());
                    }
                    LOG.info("Endpoint accepted our submission. All done.");
                } catch (IOException | URISyntaxException e) {
                    LOG.error("Error invoking AiAi (IOException): {}", e.getMessage());
                } catch (CodeGenerationException e) {
                    LOG.error("An error occurred while generating a code: " + e.getMessage());
                    LOG.error(e.getCause());
				} catch (InterruptedException e) {
                    LOG.error("An error occurred while submitting to the endpoint: " + e.getMessage());
                    LOG.error(e.getCause());
				}
            }
        }
        return null;
    }

    private String encodeValue(String value) {
        try {
			return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
		} catch (UnsupportedEncodingException e) {
            LOG.error("An error occurred while encoding the value '" + value + "': " + e.getMessage());
            return "";
		}
    }

    static class Result {
        final int status;
        final String content;

        Result(final int status, final String content) {
            this.status = status;
            this.content = content;
        }
    }

    private String generateCode() throws CodeGenerationException {
        CodeGenerator codeGenerator = new DefaultCodeGenerator();
        String totp_key = System.getenv("TOTP_KEY");
        TimeProvider timeProvider = new SystemTimeProvider();
        long epoch = timeProvider.getTime();
        long bucket = Math.floorDiv(epoch, 30);
        return codeGenerator.generate(totp_key, bucket);
    }
}
