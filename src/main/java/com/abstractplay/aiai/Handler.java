package com.abstractplay.aiai;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
            String[] envp = { "HOME=/tmp" };
            Process proc;
            try {
                proc = Runtime.getRuntime().exec("java -jar " + layerpath + "aiai.jar AI " + layerpath + "mgl/" + myMap.get("mgl") + " " + myMap.get("seed") + " " + myMap.get("ttt") + " " + myMap.get("history"), envp, new File("/opt/java/lib/aiaicli"));

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

            } catch (IOException e) {
                LOG.error("Error invoking AiAi (IOException): {}", e.getMessage());
            }
        }
        return null;
    }
}
