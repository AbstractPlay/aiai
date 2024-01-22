package com.abstractplay.aiai;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
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
import org.apache.commons.lang3.ArrayUtils;

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
            MsgInput input = new MsgInput();
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                input = objectMapper.readValue(msg.getBody(), MsgInput.class);
                if (input.meta == null) {
                    throw new Error("You must provide the game's meta ID.");
                }
                if (input.mgl == null) {
                    input.mgl = input.meta;
                }
                if (input.gameid == null) {
                    throw new Error("You must provide the game id.");
                }
                if (input.seed == null) {
                    input.seed = "-1";
                }
                if (input.ttt == null) {
                    input.ttt = "10";
                    // any game-specific TTT tweaking should happen here
                    // remember that this only gets called if TTT isn't passed
                    // anything the back end explicitly sends will override this
                    if (
                            input.meta.equals("fanorona") ||
                            input.meta.equals("lielow") ||
                            input.meta.equals("trike") ||
                            input.meta.equals("zola") ||
                            input.meta.equals("tumbleweed")
                       ) {
                        input.ttt = "1";
                    }
                    if (input.meta.equals("amazons")) {
                        input.ttt = "5";
                    }
                }
                if (input.history == null) {
                    input.history = new String[]{};
                }
                LOG.info("Parsed message body: {}", input);
            } catch (JsonProcessingException e) {
                LOG.error("An error occured while processing the message body: {}", e.getMessage());
            }

            // run it!
            if (input.mgl.equals("TEST")) {
                try {
                    LOG.info(generateCode());
                } catch (CodeGenerationException e) {
                    LOG.error("An error occurred while generating a code: " + e.getMessage());
                    LOG.error(e.getCause());
                }
            } else if (input.mgl.equals("RESIGN")) {
                try {
                    sendToEndpoint(input, "resign");
                } catch (CodeGenerationException | URISyntaxException | IOException | InterruptedException e) {
                    LOG.error("An exception occured while trying to resign the game.");
                }
            } else {
                try {
                    final AiaiResult prestate = queryState(input);
                    if (prestate.toMove == null) {
                        throw new Error("Could not extract toMove from QUERY STATE");
                    }
                    String toMove = new String(prestate.toMove);
                    List<String> moves = new ArrayList<String>();
                    while (toMove.equals(prestate.toMove)) {
                        AiaiResult result = getMove(input);
                        moves.add(result.move);
                        // add this move to list for next run
                        input.history = ArrayUtils.addAll(input.history, result.move);
                        toMove = new String(result.toMove);
                        if (result.terminal.equals("true")) {
                            break;
                        }
                    }
                    String move = String.join("|", moves);

                    // Send move to endpoint
                    sendToEndpoint(input, move);
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

    public static class MsgInput {

        public String meta;
        public String mgl;
        public String gameid;
        public String seed;
        public String ttt;
        public String[] history;

        // standard getters setters
        public MsgInput() {
            meta = null;
            mgl = null;
            gameid = null;
            seed = null;
            ttt = null;
            history = null;
        }
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

    static class AiaiResult {
        final String move;
        final String toMove;
        final String terminal;

        AiaiResult(final String move, final String toMove, final String terminal) {
            this.move = move;
            this.toMove = toMove;
            this.terminal = terminal;
        }
    }

    private AiaiResult queryState(MsgInput input) throws IOException {
        String[] cmdline = new String[] {"java", "-jar", layerpath + "aiai.jar", "QUERY", "STATE", "mgl/" + input.mgl + ".mgl", input.seed};
        cmdline = ArrayUtils.addAll(cmdline, input.history);
        LOG.info("About to execute the following command:\n" + cmdline);
        return executeExtract(cmdline);
    }

    private AiaiResult getMove(MsgInput input) throws IOException {
        String[] cmdline = new String[] {"java", "-jar", layerpath + "aiai.jar", "AI", "mgl/" + input.mgl + ".mgl", input.seed, input.ttt};
        cmdline = ArrayUtils.addAll(cmdline, input.history);
        LOG.info("About to execute the following command:\n" + cmdline);
        return executeExtract(cmdline);
    }

    private AiaiResult executeExtract(String[] cmdline) throws IOException {
        String[] envp = { "HOME=/tmp" };
        Process proc;
        proc = Runtime.getRuntime().exec(cmdline, envp, new File("/opt/java/lib/aiaicli"));

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
        String move = null;
        int left = new String(b).indexOf("<move>");
        int right = new String(b).indexOf("</move>");
        if (left != -1 && right != -1) {
            move = new String(b).substring(left + 6, right);
        }
        // extract toMove
        String toMove = null;
        left = new String(b).indexOf("<toMove>");
        right = new String(b).indexOf("</toMove>");
        if (left != -1 && right != -1) {
            toMove = new String(b).substring(left + 8, right);
        }
        // extract terminal
        String terminal = null;
        left = new String(b).indexOf("<terminal>");
        right = new String(b).indexOf("</terminal>");
        if (left != -1 && right != -1) {
            terminal = new String(b).substring(left + 10, right);
        }

        return new AiaiResult(move, toMove, terminal);
    }

    private void sendToEndpoint(MsgInput input, String move) throws CodeGenerationException, URISyntaxException, IOException, InterruptedException {
        // submit to endpoint
        String apiurl = System.getenv("API_ENDPOINT");
        if (apiurl == null) {
            throw new Error("Could not find the API_ENDPOINT environment variable.");
        }
        Map<String, String> requestParams = new HashMap<>();
        requestParams.put("query", "bot_move");
        requestParams.put("uid", "SkQfHAjeDxs8eeEnScuYA");
        requestParams.put("token", generateCode());
        requestParams.put("metaGame", input.meta);
        requestParams.put("gameid", input.gameid);
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
    }
}
