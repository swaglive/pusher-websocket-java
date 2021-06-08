package com.pusher.client.channel.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import com.pusher.client.AuthorizationFailureException;
import com.pusher.client.Authorizer;
import com.pusher.client.channel.ChannelEventListener;
import com.pusher.client.channel.ChannelState;
import com.pusher.client.channel.PresenceChannelEventListener;
import com.pusher.client.channel.PrivateChannel;
import com.pusher.client.channel.PrivateChannelEventListener;
import com.pusher.client.channel.SubscriptionEventListener;
import com.pusher.client.channel.User;
import com.pusher.client.connection.ConnectionEventListener;
import com.pusher.client.connection.ConnectionState;
import com.pusher.client.connection.ConnectionStateChange;
import com.pusher.client.connection.impl.InternalConnection;
import com.pusher.client.crypto.nacl.SecretBoxOpener;
import com.pusher.client.crypto.nacl.SecretBoxOpenerFactory;
import com.pusher.client.util.Factory;
import com.pusher.client.util.internal.Base64;

public class PrivateChannelImpl extends ChannelImpl implements PrivateChannel {

    private static final Gson GSON = new Gson();
    private static final String CLIENT_EVENT_PREFIX = "client-";
    private final InternalConnection connection;
    private final Authorizer authorizer;
    private SecretBoxOpenerFactory secretBoxOpenerFactory;
    public SecretBoxOpener secretBoxOpener;
    // For not hanging on to shared secret past the Pusher.disconnect() call,
    // i.e. when not necessary. Pusher.connect(...) call will trigger re-subscribe
    // and hence re-authenticate which creates a new secretBoxOpener.
    private ConnectionEventListener disposeSecretBoxOpenerOnDisconnectedListener =
            new ConnectionEventListener() {

                @Override
                public void onConnectionStateChange(ConnectionStateChange change) {
                    disposeSecretBoxOpener();
                }

                @Override
                public void onError(String message, String code, Exception e) {
                    // nop
                }
            };


    protected String channelData;

    public PrivateChannelImpl(final InternalConnection connection, final String channelName,
            final Authorizer authorizer, final Factory factory, final SecretBoxOpenerFactory secretBoxOpenerFactory) {
        super(channelName, factory);
        this.connection = connection;
        this.authorizer = authorizer;
        this.secretBoxOpenerFactory = secretBoxOpenerFactory;
    }

    /* PrivateChannel implementation */

    @Override
    @SuppressWarnings("rawtypes")
    public void trigger(final String eventName, final String data) {

        if (eventName == null || !eventName.startsWith(CLIENT_EVENT_PREFIX)) {
            throw new IllegalArgumentException("Cannot trigger event " + eventName
                    + ": client events must start with \"client-\"");
        }

        if (state != ChannelState.SUBSCRIBED) {
            throw new IllegalStateException("Cannot trigger event " + eventName + " because channel " + name
                    + " is in " + state.toString() + " state");
        }

        if (connection.getState() != ConnectionState.CONNECTED) {
            throw new IllegalStateException("Cannot trigger event " + eventName + " because connection is in "
                    + connection.getState().toString() + " state");
        }

        try {

            final Map<Object, Object> jsonPayload = new LinkedHashMap<Object, Object>();
            jsonPayload.put("event", eventName);
            jsonPayload.put("channel", name);
            jsonPayload.put("data", data);

            final String jsonMessage = GSON.toJson(jsonPayload);
            connection.sendMessage(jsonMessage);

        }
        catch (final JsonSyntaxException e) {
            throw new IllegalArgumentException("Cannot trigger event " + eventName + " because \"" + data
                    + "\" could not be parsed as valid JSON");
        }
    }

    /* Base class overrides */

    @Override
    public void bind(final String eventName, final SubscriptionEventListener listener) {

        if (listener instanceof PrivateChannelEventListener == false) {
            throw new IllegalArgumentException(
                    "Only instances of PrivateChannelEventListener can be bound to a private channel");
        }

        super.bind(eventName, listener);
    }


    @Override
    public void onMessage(final String event, final String message) {

        super.onMessage(event, message);

        if (event.equals(SUBSCRIPTION_SUCCESS_EVENT)) {
            handleSubscriptionSuccessfulMessage(message);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void handleSubscriptionSuccessfulMessage(final String message) {
        final ChannelEventListener listener = getEventListener();
        if (listener instanceof PrivateChannelEventListener) {
            final PrivateChannelEventListener privateListener = (PrivateChannelEventListener)listener;
            privateListener.onChannelDataReceived(getName(), channelData);
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public String toSubscribeMessage(String authResponse) {

//        final String authResponse = getAuthResponse();

        try {
            final Map authResponseMap = GSON.fromJson(authResponse, Map.class);
            final String authKey = (String)authResponseMap.get("auth");
            channelData = (String)authResponseMap.get("channel_data");
            String sharedSecret = (String) authResponseMap.get("shared_secret");
            secretBoxOpener = secretBoxOpenerFactory.create(Base64.decode(sharedSecret));
            setListenerToDisposeSecretBoxOpenerOnDisconnected();

            final Map<Object, Object> jsonObject = new LinkedHashMap<Object, Object>();
            jsonObject.put("event", "pusher:subscribe");

            final Map<Object, Object> dataMap = new LinkedHashMap<Object, Object>();
            dataMap.put("channel", name);
            dataMap.put("auth", authKey);
            if (channelData != null) {
                dataMap.put("channel_data", channelData);
            }

            jsonObject.put("data", dataMap);

            final String json = GSON.toJson(jsonObject);
            return json;
        }
        catch (final Exception e) {
            throw new AuthorizationFailureException("Unable to parse response from Authorizer: " + authResponse, e);
        }
    }

    private void setListenerToDisposeSecretBoxOpenerOnDisconnected() {
        connection.bind(ConnectionState.DISCONNECTED,
                disposeSecretBoxOpenerOnDisconnectedListener);
    }

    private void removeListenerToDisposeSecretBoxOpenerOnDisconnected() {
        connection.unbind(ConnectionState.DISCONNECTED,
                disposeSecretBoxOpenerOnDisconnectedListener);
    }

    private void disposeSecretBoxOpener() {
        if (secretBoxOpener != null) {
            secretBoxOpener.clearKey();
            secretBoxOpener = null;
            removeListenerToDisposeSecretBoxOpenerOnDisconnected();
        }
    }

    @Override
    protected String[] getDisallowedNameExpressions() {
        return new String[] {
                "^(?!private-enc-).*",  // double negative, don't not start with private-
                "^private-encrypted-.*"  // doesn't start with private-encrypted-
        };
    }

    /**
     * Protected access because this is also used by PresenceChannelImpl.
     */
    protected String getAuthResponse() {
        final String socketId = connection.getSocketId();
        return authorizer.authorize(getName(), socketId);
    }

    @Override
    public String toString() {
        return String.format("[Private Channel: name=%s]", name);
    }
}
