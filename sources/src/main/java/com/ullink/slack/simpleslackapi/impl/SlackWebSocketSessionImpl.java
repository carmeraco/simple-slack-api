package com.ullink.slack.simpleslackapi.impl;

import com.squareup.okhttp.FormEncodingBuilder;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ws.WebSocket;
import com.squareup.okhttp.ws.WebSocketCall;
import com.squareup.okhttp.ws.WebSocketListener;
import com.ullink.slack.simpleslackapi.SlackAttachment;
import com.ullink.slack.simpleslackapi.SlackChannel;
import com.ullink.slack.simpleslackapi.SlackMessageHandle;
import com.ullink.slack.simpleslackapi.SlackPersona;
import com.ullink.slack.simpleslackapi.SlackSession;
import com.ullink.slack.simpleslackapi.events.SlackChannelArchived;
import com.ullink.slack.simpleslackapi.events.SlackChannelCreated;
import com.ullink.slack.simpleslackapi.events.SlackChannelDeleted;
import com.ullink.slack.simpleslackapi.events.SlackChannelRenamed;
import com.ullink.slack.simpleslackapi.events.SlackChannelUnarchived;
import com.ullink.slack.simpleslackapi.events.SlackConnected;
import com.ullink.slack.simpleslackapi.events.SlackEvent;
import com.ullink.slack.simpleslackapi.events.SlackGroupJoined;
import com.ullink.slack.simpleslackapi.events.SlackMessageDeleted;
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted;
import com.ullink.slack.simpleslackapi.events.SlackMessageUpdated;
import com.ullink.slack.simpleslackapi.events.SlackReplyEvent;
import com.ullink.slack.simpleslackapi.impl.SlackChatConfiguration.Avatar;
import com.ullink.slack.simpleslackapi.listeners.SlackEventListener;

import org.apache.http.HttpHost;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import co.carmera.carmeraUtil.Clog;
import okio.Buffer;
import okio.BufferedSource;

class SlackWebSocketSessionImpl extends AbstractSlackSessionImpl implements SlackSession//, MessageHandler.Whole<String>
{
    private static final String SLACK_API_HTTPS_ROOT = "https://slack.com/api/";

    private static final String CHANNELS_LEAVE_COMMAND = "channels.leave";

    private static final String CHANNELS_JOIN_COMMAND = "channels.join";

    private static final String CHAT_POST_MESSAGE_COMMAND = "chat.postMessage";

    private static final String CHAT_DELETE_COMMAND = "chat.delete";

    private static final String CHAT_UPDATE_COMMAND = "chat.update";

    private static final String REACTIONS_ADD_COMMAND = "reactions.add";

    public class EventDispatcher {

        void dispatch(SlackEvent event) {
            switch (event.getEventType()) {
                case SLACK_CHANNEL_ARCHIVED:
                    dispatchImpl((SlackChannelArchived) event, channelArchiveListener);
                    break;
                case SLACK_CHANNEL_CREATED:
                    dispatchImpl((SlackChannelCreated) event, channelCreateListener);
                    break;
                case SLACK_CHANNEL_DELETED:
                    dispatchImpl((SlackChannelDeleted) event, channelDeleteListener);
                    break;
                case SLACK_CHANNEL_RENAMED:
                    dispatchImpl((SlackChannelRenamed) event, channelRenamedListener);
                    break;
                case SLACK_CHANNEL_UNARCHIVED:
                    dispatchImpl((SlackChannelUnarchived) event, channelUnarchiveListener);
                    break;
                case SLACK_GROUP_JOINED:
                    dispatchImpl((SlackGroupJoined) event, groupJoinedListener);
                    break;
                case SLACK_MESSAGE_DELETED:
                    dispatchImpl((SlackMessageDeleted) event, messageDeletedListener);
                    break;
                case SLACK_MESSAGE_POSTED:
                    dispatchImpl((SlackMessagePosted) event, messagePostedListener);
                    break;
                case SLACK_MESSAGE_UPDATED:
                    dispatchImpl((SlackMessageUpdated) event, messageUpdatedListener);
                    break;
                case SLACK_REPLY:
                    dispatchImpl((SlackReplyEvent) event, slackReplyListener);
                    break;
                case SLACK_CONNECTED:
                    dispatchImpl((SlackConnected) event, slackConnectedListener);
                    break;
                case UNKNOWN:
                    //throw new IllegalArgumentException("event not handled " + event);
                    Clog.t(TAG, "Unknown Slack Event: " + event);
                    break;
            }
        }

        private <E extends SlackEvent, L extends SlackEventListener<E>> void dispatchImpl(E event, List<L> listeners) {
            for (L listener : listeners) {
                listener.onEvent(event, SlackWebSocketSessionImpl.this);
            }
        }
    }

    private static final String TAG = "Slack";
    private static final String SLACK_HTTPS_AUTH_URL = "https://slack.com/api/rtm.start?token=";

    private WebSocket websocketSession;
    private String authToken;
    private String proxyAddress;
    private int proxyPort = -1;
    HttpHost proxyHost;
    private long lastPingSent = 0;
    private volatile long lastPingAck = 0;

    private long messageId = 0;

    private long lastConnectionTime = -1;

    private boolean reconnectOnDisconnection;

    private Map<Long, SlackMessageHandleImpl> pendingMessageMap = new ConcurrentHashMap<Long, SlackMessageHandleImpl>();

    private EventDispatcher dispatcher = new EventDispatcher();
    private boolean isConnecting = false;
    private boolean isConnected = false;

    SlackWebSocketSessionImpl(String authToken, Proxy.Type proxyType, String proxyAddress, int proxyPort, boolean reconnectOnDisconnection) {
        this.authToken = authToken;
        this.proxyAddress = proxyAddress;
        this.proxyPort = proxyPort;
        this.proxyHost = new HttpHost(proxyAddress, proxyPort);
        this.reconnectOnDisconnection = reconnectOnDisconnection;
    }

    SlackWebSocketSessionImpl(String authToken, boolean reconnectOnDisconnection) {
        this.authToken = authToken;
        this.reconnectOnDisconnection = reconnectOnDisconnection;
    }

    WebSocketListener webSocketListener = new WebSocketListener() {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Clog.d(TAG, "Websocket opened");
            websocketSession = webSocket;
            isConnecting = false;
            Clog.t(TAG, "starting connection monitoring");
            startConnectionMonitoring();
        }

        @Override
        public void onFailure(IOException e, Response response) {
            Clog.e(TAG, "Websocket Failure: " + e.toString());
            isConnecting = false;
        }

        @Override
        public void onMessage(BufferedSource payload, WebSocket.PayloadType type) throws IOException {
            String msg = payload.readUtf8();
            payload.close();
            SlackWebSocketSessionImpl.this.onMessage(msg);
        }

        @Override
        public void onPong(Buffer payload) {

        }

        @Override
        public void onClose(int code, String reason) {
            Clog.t(TAG, "Websocket closed: " + reason);
        }
    };

    public boolean isConnected() { return isConnected; }

    @Override
    public void disconnect() {
        Clog.d(TAG, "Disconnecting from the Slack server");
        closeWebsocket();
        stopConnectionMonitoring();
    }

    @Override
    public void connect() {

        try {
            Clog.d(TAG, "Slack implementation attempting to connect.");

            if (isConnecting) {
                Clog.d(TAG, "Connection in progress, aborting connect");
                return;
            }

            isConnecting = true;

            if (websocketSession != null) {
                Clog.d(TAG, "websocket session still lingering, closing..");
                disconnect();
            }

            String jsonResponse = requestSessionJSON();
            if (jsonResponse == null) return;

            SlackJSONSessionStatusParser sessionParser = parseSessionJSON(jsonResponse);
            if (sessionParser == null) return;

            users = sessionParser.getUsers();
            channels = sessionParser.getChannels();
            sessionPersona = sessionParser.getSessionPersona();
            Clog.t(TAG, users.size() + " users found on this session");
            Clog.t(TAG, channels.size() + " channels found on this session");
            String wssurl = sessionParser.getWebSocketURL();

            openWebsocket(wssurl);

            setPresence(SlackPersona.SlackPresence.AUTO);
            isConnected = true;
        } catch (Exception e) {
            Clog.w(TAG, "Error connecting to slack, retrying.", e.toString());
            isConnected = false;
            delayedConnect();
        }
    }

    private String requestSessionJSON() throws Exception {
        OkHttpClient httpClient = new OkHttpClient();

        Request request = new Request.Builder()
                .url(SLACK_HTTPS_AUTH_URL + authToken)
                .build();
        Response response;

        try {
            response = httpClient.newCall(request).execute();
            return response.body().string();
        } catch (Exception e) {
            isConnecting = false;
            throw (e);
        }
    }

    private SlackJSONSessionStatusParser parseSessionJSON(String jsonResponse) throws ConnectException {
        SlackJSONSessionStatusParser sessionParser = new SlackJSONSessionStatusParser(jsonResponse);
        try {
            sessionParser.parse();
        } catch (ParseException e1) {
            Clog.e(TAG, e1.toString());
        }
        if (sessionParser.getError() != null) {
            Clog.e(TAG, "Error during authentication : " + sessionParser.getError());
            isConnecting = false;
            throw new ConnectException(sessionParser.getError());
        }

        return sessionParser;
    }

    private void openWebsocket(String wssurl) throws Exception {
        Clog.t(TAG, "retrieved websocket URL : " + wssurl);
        Clog.t(TAG, "initiating connection to websocket");
        try {
            OkHttpClient httpClient = new OkHttpClient();
            // Timeouts don't make sense for WebSockets..
            httpClient.setReadTimeout(0, TimeUnit.NANOSECONDS);
            Request request = new Request.Builder()
                    .url(wssurl)
                    .build();
            WebSocketCall websocketCall = WebSocketCall.create(httpClient, request);
            websocketCall.enqueue(webSocketListener);
        } catch (Exception e) {
            Clog.e(TAG, e.toString());
            isConnecting = false;
            throw (e);
        }
    }

    public void delayedConnect() {
        Clog.d(TAG, "Retrying Slack connection in 10 seconds");
        Timer t = new Timer();
        t.schedule(new TimerTask() {
            @Override
            public void run() {
                connect();
            }
        }, 10000);
    }

    private void closeWebsocket() {
        if (websocketSession != null) {
            try {
                Clog.d(TAG, "Closing existing websocketSession");
                websocketSession.close(0, null);
            } catch (IOException ex) {
                // ignored.
            } finally {
                websocketSession = null;
            }
        }
    }

    private void checkConnection() throws IOException {
        if (isConnecting) {
            Clog.d(TAG, "Skipping isConnected during connection.");
            return;
        }

        if (lastPingSent > lastPingAck) {
            // disconnection happened
            Clog.i(TAG, "Connection lost... lastPingSent=" + lastPingSent + " lastPingAck=" + lastPingAck);
            disconnect();

            if (reconnectOnDisconnection)
                connect();
        } else {
            lastPingSent = getNextMessageId();
            try {
                if (websocketSession != null) {
                    Clog.t(TAG, "Ping " + lastPingSent);
                    Buffer payload = new Buffer();
                    payload.writeUtf8("{\"type\":\"ping\",\"id\":" + lastPingSent + "}");
                    payload.close();
                    websocketSession.sendMessage(WebSocket.PayloadType.TEXT, payload);
                } else if (reconnectOnDisconnection) {
                    disconnect();
                    connect();
                }
            } catch (IllegalStateException e) {
                // websocketSession might be closed in this case
                if (reconnectOnDisconnection) {
                    disconnect();
                    connect();
                }
            }
        }
    }

    private Timer monitoringTimer = null;

    private void startConnectionMonitoring() {
        lastPingSent = 0;
        lastPingAck = 0;

        monitoringTimer = new Timer();
        monitoringTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    checkConnection();
                    isConnected = true;
                } catch (IOException e) {
                    Clog.d(TAG, "Unexpected exception on monitoring timer: " + e.toString());
                    isConnected = false;
                }
            }
        }, 30000, 30000);
    }

    private void stopConnectionMonitoring() {
        if (monitoringTimer != null) {
            monitoringTimer.cancel();
            monitoringTimer = null;
        }
    }

    @Override
    public SlackMessageHandle sendMessage(SlackChannel channel, String message, SlackAttachment attachment, SlackChatConfiguration chatConfiguration) {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        Map<String, String> arguments = new HashMap<>();
        arguments.put("token", authToken);
        arguments.put("channel", channel.getId());
        arguments.put("text", message);
        if (chatConfiguration.asUser) {
            arguments.put("as_user", "true");
        }
        if (chatConfiguration.avatar == Avatar.ICON_URL) {
            arguments.put("icon_url", chatConfiguration.avatarDescription);
        }
        if (chatConfiguration.avatar == Avatar.EMOJI) {
            arguments.put("icon_emoji", chatConfiguration.avatarDescription);
        }
        if (chatConfiguration.userName != null) {
            arguments.put("username", chatConfiguration.userName);
        }
        if (attachment != null) {
            arguments.put("attachments", SlackJSONAttachmentFormatter.encodeAttachments(attachment).toString());
        }

        postSlackCommand(arguments, CHAT_POST_MESSAGE_COMMAND, handle);
        return handle;
    }

    @Override
    public SlackMessageHandle deleteMessage(String timeStamp, SlackChannel channel) {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        Map<String, String> arguments = new HashMap<>();
        arguments.put("token", authToken);
        arguments.put("channel", channel.getId());
        arguments.put("ts", timeStamp);
        postSlackCommand(arguments, CHAT_DELETE_COMMAND, handle);
        return handle;
    }

    @Override
    public SlackMessageHandle updateMessage(String timeStamp, SlackChannel channel, String message) {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        Map<String, String> arguments = new HashMap<>();
        arguments.put("token", authToken);
        arguments.put("ts", timeStamp);
        arguments.put("channel", channel.getId());
        arguments.put("text", message);
        postSlackCommand(arguments, CHAT_UPDATE_COMMAND, handle);
        return handle;
    }

    @Override
    public SlackMessageHandle addReactionToMessage(SlackChannel channel, String messageTimeStamp, String emojiCode) {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        Map<String, String> arguments = new HashMap<>();
        arguments.put("token", authToken);
        arguments.put("channel", channel.getId());
        arguments.put("timestamp", messageTimeStamp);
        arguments.put("name", emojiCode);
        postSlackCommand(arguments, REACTIONS_ADD_COMMAND, handle);
        return handle;
    }

    @Override
    public SlackMessageHandle joinChannel(String channelName) {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        Map<String, String> arguments = new HashMap<>();
        arguments.put("token", authToken);
        arguments.put("name", channelName);
        postSlackCommand(arguments, CHANNELS_JOIN_COMMAND, handle);
        return handle;
    }

    @Override
    public SlackMessageHandle leaveChannel(SlackChannel channel) {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        Map<String, String> arguments = new HashMap<>();
        arguments.put("token", authToken);
        arguments.put("channel", channel.getId());
        postSlackCommand(arguments, CHANNELS_LEAVE_COMMAND, handle);
        return handle;
    }

    private void postSlackCommand(Map<String, String> params, String command, SlackMessageHandleImpl handle) {
        OkHttpClient client = new OkHttpClient();
        FormEncodingBuilder formbuilder = new FormEncodingBuilder();

        for (Map.Entry<String, String> arg : params.entrySet()) {
            formbuilder.add(arg.getKey(), arg.getValue());
        }
        try {
            Request request = new Request.Builder()
                    .url(SLACK_API_HTTPS_ROOT + command)
                    .post(formbuilder.build())
                    .build();

            Response response = client.newCall(request).execute();
            String jsonResponse = response.body().string();
            Clog.t(TAG, "PostMessage return: " + jsonResponse);
            SlackReplyImpl reply = SlackJSONReplyParser.decode(parseObject(jsonResponse));
            handle.setSlackReply(reply);
        } catch (Exception e) {
            // TODO : improve exception handling
            e.printStackTrace();
        }
    }

    private OkHttpClient getHttpClient() {
        OkHttpClient client = new OkHttpClient();

        return client;
    }

    @Override
    public SlackMessageHandle sendMessageOverWebSocket(SlackChannel channel, String message, SlackAttachment attachment) {
        SlackMessageHandleImpl handle = new SlackMessageHandleImpl(getNextMessageId());
        try {
            JSONObject messageJSON = new JSONObject();
            messageJSON.put("type", "message");
            messageJSON.put("channel", channel.getId());
            messageJSON.put("text", message);
            if (attachment != null) {
                messageJSON.put("attachments", SlackJSONAttachmentFormatter.encodeAttachments(attachment));
            }
            Buffer payload = new Buffer();
            payload.writeUtf8(messageJSON.toJSONString());
            payload.close();
            websocketSession.sendMessage(WebSocket.PayloadType.TEXT, payload);
        } catch (Exception e) {
            // TODO : improve exception handling
            e.printStackTrace();
        }
        return handle;
    }

    public SlackPersona getSelf() {
        return sessionPersona;
    }

    @Override
    public SlackPersona.SlackPresence getPresence(SlackPersona persona) {
        OkHttpClient client = new OkHttpClient();
        FormEncodingBuilder builder = new FormEncodingBuilder();
        builder.add("token", authToken);
        builder.add("user", persona.getId());
        try {
            Request request = new Request.Builder()
                    .url("https://slack.com/api/users.getPresence")
                    .post(builder.build())
                    .build();
            Response response = client.newCall(request).execute();
            String jsonResponse = response.body().string();
            Clog.t(TAG, "PostMessage return: " + jsonResponse);
            JSONObject resultObject = parseObject(jsonResponse);

            SlackReplyImpl reply = SlackJSONReplyParser.decode(resultObject);
            if (!reply.isOk()) {
                return SlackPersona.SlackPresence.UNKNOWN;
            }
            String presence = (String) resultObject.get("presence");

            if ("active".equals(presence)) {
                return SlackPersona.SlackPresence.ACTIVE;
            }
            if ("away".equals(presence)) {
                return SlackPersona.SlackPresence.AWAY;
            }
        } catch (Exception e) {
            Clog.e(TAG, "Error getting presence", e);
        }
        return SlackPersona.SlackPresence.UNKNOWN;
    }

    @Override
    public void setPresence(SlackPersona.SlackPresence presence) {
        OkHttpClient client = new OkHttpClient();

        String presenceString = "auto";
        if (presence == SlackPersona.SlackPresence.AWAY)
            presenceString = "away";

        FormEncodingBuilder builder = new FormEncodingBuilder();
        builder.add("token", authToken);
        builder.add("presence", presenceString);
        try {
            Request request = new Request.Builder()
                    .url("https://slack.com/api/users.setPresence")
                    .post(builder.build())
                    .build();
            Response response = client.newCall(request).execute();
            String jsonResponse = response.body().string();
            Clog.t(TAG, "PostMessage return: " + jsonResponse);

        }
        catch (Exception e)
        {
            Clog.e(TAG, "Error setting presence " + e.toString());
        }
        return;
    }

    private synchronized long getNextMessageId() {
        return messageId++;
    }

    public void onMessage(String message) {
        if (message.contains("{\"type\":\"pong\",\"reply_to\"")) {
            int rightBracketIdx = message.indexOf('}');
            String toParse = message.substring(26, rightBracketIdx);
            lastPingAck = Integer.parseInt(toParse);
            Clog.t(TAG, "Pong " + lastPingAck);
        } else {
            JSONObject object = parseObject(message);
            SlackEvent slackEvent = SlackJSONMessageParser.decode(this, object);
            if (slackEvent instanceof SlackChannelCreated) {
                SlackChannelCreated slackChannelCreated = (SlackChannelCreated) slackEvent;
                channels.put(slackChannelCreated.getSlackChannel().getId(), slackChannelCreated.getSlackChannel());
            }
            if (slackEvent instanceof SlackGroupJoined) {
                SlackGroupJoined slackGroupJoined = (SlackGroupJoined) slackEvent;
                channels.put(slackGroupJoined.getSlackChannel().getId(), slackGroupJoined.getSlackChannel());
            }
            dispatcher.dispatch(slackEvent);
        }
    }

    private JSONObject parseObject(String json) {
        JSONParser parser = new JSONParser();
        try {
            JSONObject object = (JSONObject) parser.parse(json);
            return object;
        } catch (ParseException e) {
            e.printStackTrace();
            return null;
        }
    }

}
