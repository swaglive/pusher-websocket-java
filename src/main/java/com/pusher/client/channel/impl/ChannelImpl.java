package com.pusher.client.channel.impl;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;

import com.google.gson.GsonBuilder;
import com.pusher.client.channel.*;
import com.pusher.client.crypto.nacl.SecretBoxOpener;
import com.pusher.client.util.Factory;
import com.pusher.client.util.internal.Base64;

public class ChannelImpl implements InternalChannel {
    protected final Gson GSON;
    private static final String INTERNAL_EVENT_PREFIX = "pusher_internal:";
    protected static final String SUBSCRIPTION_SUCCESS_EVENT = "pusher_internal:subscription_succeeded";
    protected final String name;
    private final Map<String, Set<SubscriptionEventListener>> eventNameToListenerMap = new HashMap<String, Set<SubscriptionEventListener>>();
    protected volatile ChannelState state = ChannelState.INITIAL;
    private ChannelEventListener eventListener;
    private final Factory factory;
    private final Object lock = new Object();

    public ChannelImpl(final String channelName, final Factory factory) {
        GsonBuilder gsonBldr = new GsonBuilder();
        gsonBldr.registerTypeAdapter(PusherEvent.class, new PusherEventDeserializer());
        GSON = gsonBldr.create();
        if (channelName == null) {
            throw new IllegalArgumentException("Cannot subscribe to a channel with a null name");
        }

        for (final String disallowedPattern : getDisallowedNameExpressions()) {
            if (channelName.matches(disallowedPattern)) {
                throw new IllegalArgumentException(
                        "Channel name "
                                + channelName
                                + " is invalid. Private channel names must start with \"private-\" and presence channel names must start with \"presence-\"");
            }
        }

        name = channelName;
        this.factory = factory;
    }

    /* Channel implementation */

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void bind(final String eventName, final SubscriptionEventListener listener) {

        validateArguments(eventName, listener);

        synchronized (lock) {
            Set<SubscriptionEventListener> listeners = eventNameToListenerMap.get(eventName);
            if (listeners == null) {
                listeners = new HashSet<SubscriptionEventListener>();
                eventNameToListenerMap.put(eventName, listeners);
            }
            listeners.add(listener);
        }
    }

    @Override
    public void unbind(final String eventName, final SubscriptionEventListener listener) {

        validateArguments(eventName, listener);

        synchronized (lock) {
            final Set<SubscriptionEventListener> listeners = eventNameToListenerMap.get(eventName);
            if (listeners != null) {
                listeners.remove(listener);
                if (listeners.isEmpty()) {
                    eventNameToListenerMap.remove(eventName);
                }
            }
        }
    }

    @Override
    public boolean isSubscribed() {
        return state == ChannelState.SUBSCRIBED;
    }

    @Override
    public boolean isInitial() {
        return state == ChannelState.INITIAL;
    }

    @Override
    public ChannelState getState() {
        return state;
    }
    /* InternalChannel implementation */

    @Override
    public PusherEvent prepareEvent(String event, String message) {
        if (this instanceof PrivateChannelImpl) {
            PrivateChannelImpl p = (PrivateChannelImpl)(this);
            PusherEvent e = decryptMessage(p.secretBoxOpener, message);
            return e;
        }
        if (this instanceof PresenceChannelImpl) {
            PresenceChannelImpl p = (PresenceChannelImpl)(this);
            PusherEvent e = decryptMessage(p.secretBoxOpener, message);
            return e;
        }
        return GSON.fromJson(message, PusherEvent.class);
    }


    private class EncryptedReceivedData {
        String nonce;
        String ciphertext;

        public byte[] getNonce() {
            return Base64.decode(nonce);
        }

        public byte[] getCiphertext() {
            return Base64.decode(ciphertext);
        }
    }

    private PusherEvent decryptMessage(SecretBoxOpener opener, String message) {

        Map<String, Object> receivedMessage =
                GSON.<Map<String, Object>>fromJson(message, Map.class);

        final EncryptedReceivedData encryptedReceivedData =
                GSON.fromJson((String)receivedMessage.get("data"), EncryptedReceivedData.class);

        String decryptedData = opener.open(
                encryptedReceivedData.getCiphertext(),
                encryptedReceivedData.getNonce());

        receivedMessage.put("data", decryptedData);

        return new PusherEvent(receivedMessage);
    }

    @Override
    public void onMessage(final String event, final String message) {

        if (event.equals(SUBSCRIPTION_SUCCESS_EVENT)) {
            updateState(ChannelState.SUBSCRIBED);
        } else {
            final Set<SubscriptionEventListener> listeners = getInterestedListeners(event);
            if (listeners != null) {
                final PusherEvent pusherEvent = prepareEvent(event, message);
                if (pusherEvent != null) {
                    for (final SubscriptionEventListener listener : listeners) {
                        factory.queueOnEventThread(new Runnable() {
                            @Override
                            public void run() {
                                listener.onEvent(pusherEvent);
                            }
                        });
                    }
                }
            }
        }
    }


    @Override
    public String toSubscribeMessage(String authResponse) {

        final Map<Object, Object> jsonObject = new LinkedHashMap<Object, Object>();
        jsonObject.put("event", "pusher:subscribe");

        final Map<Object, Object> dataMap = new LinkedHashMap<Object, Object>();
        dataMap.put("channel", name);

        jsonObject.put("data", dataMap);

        return GSON.toJson(jsonObject);
    }

    @Override
    public String toUnsubscribeMessage() {
        final Map<Object, Object> jsonObject = new LinkedHashMap<Object, Object>();
        jsonObject.put("event", "pusher:unsubscribe");

        final Map<Object, Object> dataMap = new LinkedHashMap<Object, Object>();
        dataMap.put("channel", name);

        jsonObject.put("data", dataMap);

        return GSON.toJson(jsonObject);
    }

    @Override
    public void updateState(final ChannelState state) {

        this.state = state;

        if (state == ChannelState.SUBSCRIBED && eventListener != null) {
            factory.queueOnEventThread(new Runnable() {
                @Override
                public void run() {
                    eventListener.onSubscriptionSucceeded(ChannelImpl.this.getName());
                }
            });
        }
    }

    /* Comparable implementation */

    @Override
    public void setEventListener(final ChannelEventListener listener) {
        eventListener = listener;
    }

    @Override
    public ChannelEventListener getEventListener() {
        return eventListener;
    }

    @Override
    public int compareTo(final InternalChannel other) {
        return getName().compareTo(other.getName());
    }

    /* implementation detail */

    @Override
    public String toString() {
        return String.format("[Public Channel: name=%s]", name);
    }


    protected String[] getDisallowedNameExpressions() {
        return new String[] { "^private-enc-.*", "^presence-enc-.*" };
    }

    private void validateArguments(final String eventName, final SubscriptionEventListener listener) {

        if (eventName == null) {
            throw new IllegalArgumentException("Cannot bind or unbind to channel " + name + " with a null event name");
        }

        if (listener == null) {
            throw new IllegalArgumentException("Cannot bind or unbind to channel " + name + " with a null listener");
        }

        if (eventName.startsWith(INTERNAL_EVENT_PREFIX)) {
            throw new IllegalArgumentException("Cannot bind or unbind channel " + name
                    + " with an internal event name such as " + eventName);
        }

        if (state == ChannelState.UNSUBSCRIBED) {
            throw new IllegalStateException(
                    "Cannot bind or unbind to events on a channel that has been unsubscribed. Call Pusher.subscribe() to resubscribe to this channel");
        }
    }

    protected Set<SubscriptionEventListener> getInterestedListeners(String event) {
        synchronized (lock) {

            final Set<SubscriptionEventListener> sharedListeners =
                    eventNameToListenerMap.get(event);

            if (sharedListeners == null) {
                return null;
            }

            return new HashSet<>(sharedListeners);
        }
    }
}
