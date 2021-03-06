/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package net.java.sip.communicator.impl.protocol.jabber;

import java.util.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.carbon.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.mailnotification.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.messagecorrection.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.Message;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.service.protocol.jabberconstants.*;
import net.java.sip.communicator.util.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.*;
import org.jivesoftware.smack.util.*;
import org.jivesoftware.smackx.*;
import org.jivesoftware.smackx.packet.*;

/**
 * A straightforward implementation of the basic instant messaging operation
 * set.
 *
 * @author Damian Minkov
 * @author Matthieu Helleringer
 * @author Alain Knaebel
 * @author Emil Ivov
 */
public class OperationSetBasicInstantMessagingJabberImpl
    extends AbstractOperationSetBasicInstantMessaging
    implements OperationSetMessageCorrection
{
    /**
     * Our class logger
     */
    private static final Logger logger =
        Logger.getLogger(OperationSetBasicInstantMessagingJabberImpl.class);

    /**
     * The maximum number of unread threads that we'd be notifying the user of.
     */
    private static final String PNAME_MAX_GMAIL_THREADS_PER_NOTIFICATION
        = "net.java.sip.communicator.impl.protocol.jabber."
            +"MAX_GMAIL_THREADS_PER_NOTIFICATION";

    /**
     * A table mapping contact addresses to full jids that can be used to
     * target a specific resource (rather than sending a message to all logged
     * instances of a user).
     */
    private Map<String, TargetAddress> jids
                                    = new Hashtable<String, TargetAddress>();

    /**
     * The smackMessageListener instance listens for incoming messages.
     * Keep a reference of it so if anything goes wrong we don't add
     * two different instances.
     */
    private SmackMessageListener smackMessageListener = null;

    /**
     * Contains the complete jid of a specific user and the time that it was
     * last used so that we could remove it after a certain point.
     */
    private class TargetAddress
    {
        /** The last complete JID (including resource) that we got a msg from*/
        String jid;

        /** The time that we last sent or received a message from this jid */
        long lastUpdatedTime;
    }

    /**
     * The number of milliseconds that we preserve threads with no traffic
     * before considering them dead.
     */
    private static final long JID_INACTIVITY_TIMEOUT = 10*60*1000;//10 min.

    /**
     * Indicates the time of the last Mailbox report that we received from
     * Google (if this is a Google server we are talking to). Should be included
     * in all following mailbox queries
     */
    private long lastReceivedMailboxResultTime = -1;

    /**
     * The provider that created us.
     */
    private final ProtocolProviderServiceJabberImpl jabberProvider;

    /**
     * A reference to the persistent presence operation set that we use
     * to match incoming messages to <tt>Contact</tt>s and vice versa.
     */
    private OperationSetPersistentPresenceJabberImpl opSetPersPresence = null;

    /**
     * The opening BODY HTML TAG: &ltbody&gt
     */
    private static final String OPEN_BODY_TAG = "<body>";

    /**
     * The closing BODY HTML TAG: &ltbody&gt
     */
    private static final String CLOSE_BODY_TAG = "</body>";

    /**
     * The html namespace used as feature
     * XHTMLManager.namespace
     */
    private final static String HTML_NAMESPACE =
        "http://jabber.org/protocol/xhtml-im";

    /**
     * List of filters to be used to filter which messages to handle
     * current Operation Set.
     */
    private List<PacketFilter> packetFilters = new ArrayList<PacketFilter>();

    /**
     * Whether carbon is enabled or not.
     */
    private boolean isCarbonEnabled = false;

    /**
     * Creates an instance of this operation set.
     * @param provider a reference to the <tt>ProtocolProviderServiceImpl</tt>
     * that created us and that we'll use for retrieving the underlying aim
     * connection.
     */
    OperationSetBasicInstantMessagingJabberImpl(
        ProtocolProviderServiceJabberImpl provider)
    {
        this.jabberProvider = provider;

        packetFilters.add(new GroupMessagePacketFilter());
        packetFilters.add(
            new PacketTypeFilter(org.jivesoftware.smack.packet.Message.class));

        provider.addRegistrationStateChangeListener(
                        new RegistrationStateListener());

        ProviderManager man = ProviderManager.getInstance();
        MessageCorrectionExtensionProvider extProvider =
                new MessageCorrectionExtensionProvider();
        man.addExtensionProvider(MessageCorrectionExtension.ELEMENT_NAME,
                MessageCorrectionExtension.NAMESPACE,
                extProvider);
    }

    /**
     * Create a Message instance with the specified UID, content type
     * and a default encoding.
     * This method can be useful when message correction is required. One can
     * construct the corrected message to have the same UID as the message
     * before correction.
     *
     * @param messageText the string content of the message.
     * @param contentType the MIME-type for <tt>content</tt>
     * @param messageUID the unique identifier of this message.
     * @return Message the newly created message
     */
    public Message createMessageWithUID(
        String messageText, String contentType, String messageUID)
    {
        return new MessageJabberImpl(messageText, contentType,
            DEFAULT_MIME_ENCODING, null, messageUID);
    }

    /**
     * Create a Message instance for sending arbitrary MIME-encoding content.
     *
     * @param content content value
     * @param contentType the MIME-type for <tt>content</tt>
     * @return the newly created message.
     */
    public Message createMessage(String content, String contentType)
    {
        return createMessage(content, contentType, DEFAULT_MIME_ENCODING, null);
    }

    /**
     * Create a Message instance for sending arbitrary MIME-encoding content.
     *
     * @param content content value
     * @param contentType the MIME-type for <tt>content</tt>
     * @param subject the Subject of the message that we'd like to create.
     * @param encoding the enconding of the message that we will be sending.
     *
     * @return the newly created message.
     */
    @Override
    public Message createMessage(String content, String contentType,
        String encoding, String subject)
    {
        return new MessageJabberImpl(content, contentType, encoding, subject);
    }

    Message createMessage(String content, String contentType,
            String messageUID)
    {
        return new MessageJabberImpl(content, contentType,
                DEFAULT_MIME_ENCODING, null, messageUID);
    }

    /**
     * Determines wheter the protocol provider (or the protocol itself) support
     * sending and receiving offline messages. Most often this method would
     * return true for protocols that support offline messages and false for
     * those that don't. It is however possible for a protocol to support these
     * messages and yet have a particular account that does not (i.e. feature
     * not enabled on the protocol server). In cases like this it is possible
     * for this method to return true even when offline messaging is not
     * supported, and then have the sendMessage method throw an
     * OperationFailedException with code - OFFLINE_MESSAGES_NOT_SUPPORTED.
     *
     * @return <tt>true</tt> if the protocol supports offline messages and
     * <tt>false</tt> otherwise.
     */
    public boolean isOfflineMessagingSupported()
    {
        return true;
    }

    /**
     * Determines wheter the protocol supports the supplied content type
     *
     * @param contentType the type we want to check
     * @return <tt>true</tt> if the protocol supports it and
     * <tt>false</tt> otherwise.
     */
    public boolean isContentTypeSupported(String contentType)
    {
        return
            (contentType.equals(DEFAULT_MIME_TYPE)
                || contentType.equals(HTML_MIME_TYPE));
    }

    /**
     * Determines whether the protocol supports the supplied content type
     * for the given contact.
     *
     * @param contentType the type we want to check
     * @param contact contact which is checked for supported contentType
     * @return <tt>true</tt> if the contact supports it and
     * <tt>false</tt> otherwise.
     */
    @Override
    public boolean isContentTypeSupported(String contentType, Contact contact)
    {
        // by default we support default mime type, for other mimetypes
        // method must be overriden
        if(contentType.equals(DEFAULT_MIME_TYPE))
            return true;
        else if(contentType.equals(HTML_MIME_TYPE))
        {
            String toJID = getJidForAddress(contact.getAddress());

            if (toJID == null)
                toJID = contact.getAddress();

            return jabberProvider.isFeatureListSupported(
                        toJID,
                        HTML_NAMESPACE);
        }

        return false;
    }

    /**
     * Returns a reference to an open chat with the specified
     * <tt>jid</tt> if one exists or creates a new one otherwise.
     *
     * @param jid the Jabber ID that we'd like to obtain a chat instance for.
     *
     * @return a reference to an open chat with the specified
     * <tt>jid</tt> if one exists or creates a new one otherwise.
     */
    public Chat obtainChatInstance(String jid)
    {
        XMPPConnection jabberConnection
            = jabberProvider.getConnection();

        Chat chat = jabberConnection.getChatManager().getThreadChat(jid);

        if (chat != null)
            return chat;

        org.jivesoftware.smack.MessageListener msgListener
            = new org.jivesoftware.smack.MessageListener()
            {
                public void processMessage(
                    Chat chat,
                    org.jivesoftware.smack.packet.Message message)
                {
                    //we are not fully supporting chat based messaging
                    //right now and only use a hack to make it look that
                    //way. as a result we don't listen on the chat
                    //itself and the only thing we do here is an update
                    //of the active thread timestamp.
                }
            };


        //we don't have a thread for this chat, so let's create one.
        chat = jabberConnection.getChatManager()
                .createChat(jid, msgListener);

        return chat;
    }

    /**
     * Remove from our <tt>jids</tt> map all entries that have not seen any
     * activity (i.e. neither outgoing nor incoming messags) for more than
     * JID_INACTIVITY_TIMEOUT. Note that this method is not synchronous and that
     * it is only meant for use by the {@link #getJidForAddress(String)} and
     * {@link #putJidForAddress(String, String)}
     */
    private void purgeOldJids()
    {
        long currentTime = System.currentTimeMillis();

        Iterator<Map.Entry<String, TargetAddress>> entries
            = jids.entrySet().iterator();


        while( entries.hasNext() )
        {
            Map.Entry<String, TargetAddress> entry = entries.next();
            TargetAddress target = entry.getValue();

            if (currentTime - target.lastUpdatedTime
                            > JID_INACTIVITY_TIMEOUT)
                entries.remove();
        }
    }

    /**
     * Returns the last jid that the party with the specified <tt>address</tt>
     * contacted us from or <tt>null</tt>(or bare jid) if we don't have a jid
     * for the specified <tt>address</tt> yet. The method would also purge all
     * entries that haven't seen any activity (i.e. no one has tried to get or
     * remap it) for a delay longer than <tt>JID_INACTIVITY_TIMEOUT</tt>.
     *
     * @param address the <tt>address</tt> that we'd like to obtain a jid for.
     *
     * @return the last jid that the party with the specified <tt>address</tt>
     * contacted us from or <tt>null</tt> if we don't have a jid for the
     * specified <tt>address</tt> yet.
     */
    String getJidForAddress(String address)
    {
        synchronized(jids)
        {
            purgeOldJids();
            TargetAddress ta = jids.get(address);

            if (ta == null)
                return null;

            ta.lastUpdatedTime = System.currentTimeMillis();

            return ta.jid;
        }
    }

    /**
     * Maps the specified <tt>address</tt> to <tt>jid</tt>. The point of this
     * method is to allow us to send all messages destined to the contact with
     * the specified <tt>address</tt> to the <tt>jid</tt> that they last
     * contacted us from.
     *
     * @param address the bare address (i.e. no resource included) of the
     * contact that we'd like to set a jid for.
     * @param jid the jid (i.e. address/resource) that the contact with the
     * specified <tt>address</tt> last contacted us from.
     */
    private void putJidForAddress(String address, String jid)
    {
        synchronized(jids)
        {
            purgeOldJids();

            TargetAddress ta = jids.get(address);

            if (ta == null)
            {
                ta = new TargetAddress();
                jids.put(address, ta);
            }

            ta.jid = jid;
            ta.lastUpdatedTime = System.currentTimeMillis();
        }
    }

    /**
     * Helper function used to send a message to a contact, with the given
     * extensions attached.
     *
     * @param to The contact to send the message to.
     * @param toResource The resource to send the message to or null if no
     * resource has been specified
     * @param message The message to send.
     * @param extensions The XMPP extensions that should be attached to the
     * message before sending.
     * @return The MessageDeliveryEvent that resulted after attempting to
     * send this message, so the calling function can modify it if needed.
     */
    private MessageDeliveredEvent sendMessage(  Contact to,
                                                ContactResource toResource,
                                                Message message,
                                                PacketExtension[] extensions)
    {
        if( !(to instanceof ContactJabberImpl) )
           throw new IllegalArgumentException(
               "The specified contact is not a Jabber contact."
               + to);

        try
        {
            assertConnected();

            org.jivesoftware.smack.packet.Message msg =
                new org.jivesoftware.smack.packet.Message();

            String toJID = null;

            boolean sendToBaseResource = false;
            if (toResource != null)
            {
                if(toResource.equals(ContactResource.BASE_RESOURCE))
                {
                    toJID = to.getAddress();
                    sendToBaseResource = true;
                }
                else
                    toJID =
                        ((ContactResourceJabberImpl) toResource).getFullJid();
            }

            if (toJID == null)
                toJID = getJidForAddress(to.getAddress());

            if (toJID == null)
            {
                sendToBaseResource = true;
                toJID = to.getAddress();
            }

            Chat chat = obtainChatInstance(toJID);

            msg.setPacketID(message.getMessageUID());
            msg.setTo(toJID);

            for (PacketExtension ext : extensions)
            {
                msg.addExtension(ext);
            }

            if (logger.isTraceEnabled())
                logger.trace("Will send a message to:" + toJID
                            + " chat.jid=" + chat.getParticipant()
                            + " chat.tid=" + chat.getThreadID());

            MessageDeliveredEvent msgDeliveryPendingEvt
                = new MessageDeliveredEvent(message, to, toResource);

            msgDeliveryPendingEvt
                = messageDeliveryPendingTransform(msgDeliveryPendingEvt);

            if (msgDeliveryPendingEvt == null)
                return null;

            String content = msgDeliveryPendingEvt
                                    .getSourceMessage().getContent();

            if(message.getContentType().equals(HTML_MIME_TYPE))
            {
                msg.setBody(Html2Text.extractText(content));

                // Check if the other user supports XHTML messages
                // make sure we use our discovery manager as it caches calls
                if(jabberProvider.isFeatureListSupported(
                        chat.getParticipant(),
                        HTML_NAMESPACE))
                {
                    // Add the XHTML text to the message
                    XHTMLManager.addBody(msg,
                        OPEN_BODY_TAG + content + CLOSE_BODY_TAG);
                }
            }
            else
            {
                // this is plain text so keep it as it is.
                msg.setBody(content);
            }

            //msg.addExtension(new Version());

            if(msgDeliveryPendingEvt.isMessageEncrypted())
            {
                msg.addExtension(new CarbonPacketExtension.PrivateExtension());
            }

            MessageEventManager.
                addNotificationsRequests(msg, true, false, false, true);

            chat.sendMessage(msg);

            if(sendToBaseResource)
                putJidForAddress(to.getAddress(), to.getAddress());

            MessageDeliveredEvent msgDeliveredEvt
                = new MessageDeliveredEvent(message, to, toResource);

            // msgDeliveredEvt = messageDeliveredTransform(msgDeliveredEvt);

            return msgDeliveredEvt;
        }
        catch (XMPPException ex)
        {
            logger.error("message not sent", ex);
            return null;
        }
    }

    /**
     * Sends the <tt>message</tt> to the destination indicated by the
     * <tt>to</tt> contact.
     *
     * @param to the <tt>Contact</tt> to send <tt>message</tt> to
     * @param message the <tt>Message</tt> to send.
     * @throws java.lang.IllegalStateException if the underlying stack is
     * not registered and initialized.
     * @throws java.lang.IllegalArgumentException if <tt>to</tt> is not an
     * instance of ContactImpl.
     */
    public void sendInstantMessage(Contact to, Message message)
        throws IllegalStateException, IllegalArgumentException
    {
        sendInstantMessage(to, null, message);
    }

    /**
     * Sends the <tt>message</tt> to the destination indicated by the
     * <tt>to</tt>. Provides a default implementation of this method.
     *
     * @param to the <tt>Contact</tt> to send <tt>message</tt> to
     * @param toResource the resource to which the message should be send
     * @param message the <tt>Message</tt> to send.
     * @throws java.lang.IllegalStateException if the underlying ICQ stack is
     * not registered and initialized.
     * @throws java.lang.IllegalArgumentException if <tt>to</tt> is not an
     * instance belonging to the underlying implementation.
     */
    @Override
    public void sendInstantMessage( Contact to,
                                    ContactResource toResource,
                                    Message message)
        throws  IllegalStateException,
                IllegalArgumentException
    {
        MessageDeliveredEvent msgDelivered =
            sendMessage(to, toResource, message, new PacketExtension[0]);

        fireMessageEvent(msgDelivered);
    }

    /**
     * Replaces the message with ID <tt>correctedMessageUID</tt> sent to
     * the contact <tt>to</tt> with the message <tt>message</tt>
     *
     * @param to The contact to send the message to.
     * @param message The new message.
     * @param correctedMessageUID The ID of the message being replaced.
     */
    public void correctMessage(
        Contact to, ContactResource resource,
        Message message, String correctedMessageUID)
    {
        PacketExtension[] exts = new PacketExtension[1];
        exts[0] = new MessageCorrectionExtension(correctedMessageUID);
        MessageDeliveredEvent msgDelivered
            = sendMessage(to, resource, message, exts);
        msgDelivered.setCorrectedMessageUID(correctedMessageUID);
        fireMessageEvent(msgDelivered);
    }

    /**
     * Utility method throwing an exception if the stack is not properly
     * initialized.
     *
     * @throws java.lang.IllegalStateException if the underlying stack is
     * not registered and initialized.
     */
    private void assertConnected()
        throws IllegalStateException
    {
        if (opSetPersPresence == null)
        {
            throw
                new IllegalStateException(
                        "The provider must be signed on the service before"
                            + " being able to communicate.");
        }
        else
            opSetPersPresence.assertConnected();
    }

    /**
     * Our listener that will tell us when we're registered to
     */
    private class RegistrationStateListener
        implements RegistrationStateChangeListener
    {
        /**
         * The method is called by a ProtocolProvider implementation whenever
         * a change in the registration state of the corresponding provider had
         * occurred.
         * @param evt ProviderStatusChangeEvent the event describing the status
         * change.
         */
        public void registrationStateChanged(RegistrationStateChangeEvent evt)
        {
            if (logger.isDebugEnabled())
                logger.debug("The provider changed state from: "
                         + evt.getOldState()
                         + " to: " + evt.getNewState());

            if (evt.getNewState() == RegistrationState.REGISTERING)
            {
                opSetPersPresence
                    = (OperationSetPersistentPresenceJabberImpl)
                        jabberProvider.getOperationSet(
                                OperationSetPersistentPresence.class);

                if(smackMessageListener == null)
                {
                    smackMessageListener = new SmackMessageListener();
                }
                else
                {
                    // make sure this listener is not already installed in this
                    // connection
                    jabberProvider.getConnection()
                        .removePacketListener(smackMessageListener);
                }

                jabberProvider.getConnection().addPacketListener(
                        smackMessageListener,
                        new AndFilter(
                            packetFilters.toArray(
                                new PacketFilter[packetFilters.size()])));
            }
            else if (evt.getNewState() == RegistrationState.REGISTERED)
            {
                //subscribe for Google (Gmail or Google Apps) notifications
                //for new mail messages.
                boolean enableGmailNotifications
                   = jabberProvider
                       .getAccountID()
                           .getAccountPropertyBoolean(
                               "GMAIL_NOTIFICATIONS_ENABLED",
                               false);

                if (enableGmailNotifications)
                    subscribeForGmailNotifications();

                boolean enableCarbon
                    = isCarbonSupported() && !jabberProvider.getAccountID()
                            .getAccountPropertyBoolean(
                                ProtocolProviderFactory.IS_CARBON_DISABLED,
                                false);
                if(enableCarbon)
                {
                    enableDisableCarbon(true);
                }
                else
                {
                    isCarbonEnabled = false;
                }
            }
            else if(evt.getNewState() == RegistrationState.UNREGISTERED
                || evt.getNewState() == RegistrationState.CONNECTION_FAILED
                || evt.getNewState() == RegistrationState.AUTHENTICATION_FAILED)
            {
                if(jabberProvider.getConnection() != null)
                {
                    if(smackMessageListener != null)
                        jabberProvider.getConnection().removePacketListener(
                            smackMessageListener);
                }

                smackMessageListener = null;
            }
        }

    }


    /**
     * Sends enable or disable carbon packet to the server.
     * @param enable if <tt>true</tt> sends enable packet otherwise sends
     * disable packet.
     */
    private void enableDisableCarbon(final boolean enable)
    {
        IQ iq = new IQ(){

            @Override
            public String getChildElementXML()
            {
                return "<" + (enable? "enable" : "disable") + " xmlns='urn:xmpp:carbons:2' />";
            }

        };

        Packet response = null;
        try
        {
            PacketCollector packetCollector
                = jabberProvider.getConnection().createPacketCollector(
                        new PacketIDFilter(iq.getPacketID()));
            iq.setFrom(jabberProvider.getOurJID());
            iq.setType(IQ.Type.SET);
            jabberProvider.getConnection().sendPacket(iq);
            response
                = packetCollector.nextResult(
                        SmackConfiguration.getPacketReplyTimeout());

            packetCollector.cancel();
        }
        catch(Exception e)
        {
            logger.error("Failed to enable carbon.", e);
        }

        isCarbonEnabled = false;

        if (response == null)
        {
            logger.error(
                    "Failed to enable carbon. No response is received.");
        }
        else if (response.getError() != null)
        {
            logger.error(
                    "Failed to enable carbon: "
                        + response.getError());
        }
        else if (!(response instanceof IQ)
            || !((IQ) response).getType().equals(IQ.Type.RESULT))
        {
            logger.error(
                    "Failed to enable carbon. The response is not correct.");
        }
        else
        {
            isCarbonEnabled = true;
        }

    }

    /**
     * Checks whether the carbon is supported by the server or not.
     * @return <tt>true</tt> if carbon is supported by the server and
     * <tt>false</tt> if not.
     */
    private boolean isCarbonSupported()
    {
        try
        {
            return jabberProvider.getDiscoveryManager().discoverInfo(
                jabberProvider.getAccountID().getService())
                .containsFeature(CarbonPacketExtension.NAMESPACE);
        }
        catch (XMPPException e)
        {
           logger.error("Failed to retrieve carbon support.",e);
        }
        return false;
    }

    /**
     * The listener that we use in order to handle incoming messages.
     */
    @SuppressWarnings("unchecked")
    private class SmackMessageListener
        implements PacketListener
    {
        /**
         * Handles incoming messages and dispatches whatever events are
         * necessary.
         * @param packet the packet that we need to handle (if it is a message).
         */
        public void processPacket(Packet packet)
        {
            if(!(packet instanceof org.jivesoftware.smack.packet.Message))
                return;

            org.jivesoftware.smack.packet.Message msg =
                (org.jivesoftware.smack.packet.Message)packet;

            boolean isForwardedSentMessage = false;
            if(msg.getBody() == null)
            {

                CarbonPacketExtension carbonExt
                    = (CarbonPacketExtension) msg.getExtension(
                        CarbonPacketExtension.NAMESPACE);
                if(carbonExt == null)
                    return;

                isForwardedSentMessage
                    = (carbonExt.getElementName()
                        == CarbonPacketExtension.SENT_ELEMENT_NAME);
                List<ForwardedPacketExtension> extensions
                    = carbonExt.getChildExtensionsOfType(
                        ForwardedPacketExtension.class);
                if(extensions.isEmpty())
                    return;
                ForwardedPacketExtension forwardedExt = extensions.get(0);
                msg = forwardedExt.getMessage();
                if(msg == null || msg.getBody() == null)
                    return;

            }

            Object multiChatExtension =
                msg.getExtension("x", "http://jabber.org/protocol/muc#user");

            // its not for us
            if(multiChatExtension != null)
                return;

            String userFullId
                = isForwardedSentMessage? msg.getTo() : msg.getFrom();

            String userBareID = StringUtils.parseBareAddress(userFullId);

            boolean isPrivateMessaging = false;
            ChatRoom privateContactRoom = ((OperationSetMultiUserChatJabberImpl)
                jabberProvider.getOperationSet(OperationSetMultiUserChat.class))
                    .getChatRoom(userBareID);
            if(privateContactRoom != null)
            {
                isPrivateMessaging = true;
            }

            if(logger.isDebugEnabled())
            {
                if (logger.isDebugEnabled())
                    logger.debug("Received from "
                             + userBareID
                             + " the message "
                             + msg.toXML());
            }

            Message newMessage = createMessage(msg.getBody(),
                    DEFAULT_MIME_TYPE, msg.getPacketID());

            //check if the message is available in xhtml
            PacketExtension ext = msg.getExtension(
                            "http://jabber.org/protocol/xhtml-im");

            if(ext != null)
            {
                XHTMLExtension xhtmlExt
                    = (XHTMLExtension)ext;

                //parse all bodies
                Iterator<String> bodies = xhtmlExt.getBodies();
                StringBuffer messageBuff = new StringBuffer();
                while (bodies.hasNext())
                {
                    String body = bodies.next();
                    messageBuff.append(body);
                }

                if (messageBuff.length() > 0)
                {
                    // we remove body tags around message cause their
                    // end body tag is breaking
                    // the visualization as html in the UI
                    String receivedMessage =
                        messageBuff.toString()
                        // removes body start tag
                        .replaceAll("\\<[bB][oO][dD][yY].*?>","")
                        // removes body end tag
                        .replaceAll("\\</[bB][oO][dD][yY].*?>","");

                    // for some reason &apos; is not rendered correctly
                    // from our ui, lets use its equivalent. Other
                    // similar chars(< > & ") seem ok.
                    receivedMessage =
                            receivedMessage.replaceAll("&apos;", "&#39;");

                    newMessage = createMessage(receivedMessage,
                            HTML_MIME_TYPE, msg.getPacketID());
                }
            }

            PacketExtension correctionExtension =
                    msg.getExtension(MessageCorrectionExtension.NAMESPACE);
            String correctedMessageUID = null;
            if (correctionExtension != null)
            {
                correctedMessageUID = ((MessageCorrectionExtension)
                        correctionExtension).getCorrectedMessageUID();
            }

            Contact sourceContact
                = opSetPersPresence.findContactByID(
                    (isPrivateMessaging? userFullId : userBareID));
            if(msg.getType()
                            == org.jivesoftware.smack.packet.Message.Type.error)
            {
                // error which is multichat and we don't know about the contact
                // is a muc message error which is missing muc extension
                // and is coming from the room, when we try to send message to
                // room which was deleted or offline on the server
                if(isPrivateMessaging && sourceContact == null)
                {
                    if(privateContactRoom != null)
                    {
                        XMPPError error = packet.getError();
                        int errorResultCode
                            = ChatRoomMessageDeliveryFailedEvent.UNKNOWN_ERROR;
                        String errorReason = error.getMessage();

                        ChatRoomMessageDeliveryFailedEvent evt =
                            new ChatRoomMessageDeliveryFailedEvent(
                                privateContactRoom,
                                null,
                                errorResultCode,
                                errorReason,
                                new Date(),
                                newMessage);
                        ((ChatRoomJabberImpl)privateContactRoom)
                            .fireMessageEvent(evt);
                    }

                    return;
                }

                if (logger.isInfoEnabled())
                    logger.info("Message error received from " + userBareID);

                int errorCode = packet.getError().getCode();
                int errorResultCode = MessageDeliveryFailedEvent.UNKNOWN_ERROR;

                if(errorCode == 503)
                {
                    org.jivesoftware.smackx.packet.MessageEvent msgEvent =
                        (org.jivesoftware.smackx.packet.MessageEvent)
                            packet.getExtension("x", "jabber:x:event");
                    if(msgEvent != null && msgEvent.isOffline())
                    {
                        errorResultCode =
                            MessageDeliveryFailedEvent
                                .OFFLINE_MESSAGES_NOT_SUPPORTED;
                    }
                }

                MessageDeliveryFailedEvent ev
                    = new MessageDeliveryFailedEvent(newMessage,
                                                     sourceContact,
                                                     correctedMessageUID,
                                                     errorResultCode);

                // ev = messageDeliveryFailedTransform(ev);

                if (ev != null)
                    fireMessageEvent(ev);
                return;
            }
            //cache the jid (resource included) of the contact that's sending us
            //a message so that all following messages would go to the resource
            //that they contacted us from.
            String address = userBareID;
            if(isPrivateMessaging)
            {
                address = JabberActivator.getResources().getI18NString(
                        "service.gui.FROM",
                        new String[]{
                            StringUtils.parseResource(msg.getFrom()),
                            userBareID} );
            }

            putJidForAddress(address, userFullId);

            if (logger.isTraceEnabled())
                logger.trace("just mapped: " + userBareID
                                + " to " + msg.getFrom());

            // In the second condition we filter all group chat messages,
            // because they are managed by the multi user chat operation set.
            if(sourceContact == null)
            {
                if (logger.isDebugEnabled())
                    logger.debug("received a message from an unknown contact: "
                                   + userBareID);
                //create the volatile contact
                sourceContact = opSetPersPresence
                    .createVolatileContact(
                        userFullId,
                        isPrivateMessaging);
            }

            Date timestamp = new Date();
            //Check for XEP-0091 timestamp (deprecated)
            PacketExtension delay = msg.getExtension("x", "jabber:x:delay");
            if(delay != null && delay instanceof DelayInformation)
            {
                timestamp = ((DelayInformation)delay).getStamp();
            }
            //check for XEP-0203 timestamp
            delay = msg.getExtension("delay", "urn:xmpp:delay");
            if(delay != null && delay instanceof DelayInfo)
            {
                timestamp = ((DelayInfo)delay).getStamp();
            }

            ContactResource resource = ((ContactJabberImpl) sourceContact)
                    .getResourceFromJid(userFullId);

            EventObject msgEvt = null;
            if(!isForwardedSentMessage)
                msgEvt
                    = new MessageReceivedEvent( newMessage,
                                                sourceContact,
                                                resource,
                                                timestamp,
                                                correctedMessageUID,
                                                isPrivateMessaging,
                                                privateContactRoom);
            else
                msgEvt = new MessageDeliveredEvent(newMessage, sourceContact, timestamp);
            // msgReceivedEvt = messageReceivedTransform(msgReceivedEvt);
            if (msgEvt != null)
                fireMessageEvent(msgEvt);
        }
    }


    /**
     * A filter that prevents this operation set from handling multi user chat
     * messages.
     */
    private static class GroupMessagePacketFilter implements PacketFilter
    {
        /**
         * Returns <tt>true</tt> if <tt>packet</tt> is a <tt>Message</tt> and
         * false otherwise.
         *
         * @param packet the packet that we need to check.
         *
         * @return  <tt>true</tt> if <tt>packet</tt> is a <tt>Message</tt> and
         * false otherwise.
         */
        public boolean accept(Packet packet)
        {
            if(!(packet instanceof org.jivesoftware.smack.packet.Message))
                return false;

            org.jivesoftware.smack.packet.Message msg
                = (org.jivesoftware.smack.packet.Message) packet;

            return
                !msg.getType().equals(
                        org.jivesoftware.smack.packet.Message.Type.groupchat);
        }
    }

    /**
     * Subscribes this provider as interested in receiving notifications for
     * new mail messages from Google mail services such as Gmail or Google Apps.
     */
    private void subscribeForGmailNotifications()
    {
        // first check support for the notification service
        String accountIDService = jabberProvider.getAccountID().getService();
        boolean notificationsAreSupported
            = jabberProvider.isFeatureSupported(
                    accountIDService,
                    NewMailNotificationIQ.NAMESPACE);

        if (!notificationsAreSupported)
        {
            if (logger.isDebugEnabled())
                logger.debug(accountIDService
                        +" does not seem to provide a Gmail notification "
                        +" service so we won't be trying to subscribe for it");
            return;
        }

        if (logger.isDebugEnabled())
            logger.debug(accountIDService
                        +" seems to provide a Gmail notification "
                        +" service so we will try to subscribe for it");

        ProviderManager providerManager = ProviderManager.getInstance();

        providerManager.addIQProvider(
                MailboxIQ.ELEMENT_NAME,
                MailboxIQ.NAMESPACE,
                new MailboxIQProvider());
        providerManager.addIQProvider(
                NewMailNotificationIQ.ELEMENT_NAME,
                NewMailNotificationIQ.NAMESPACE,
                new NewMailNotificationProvider());

        XMPPConnection connection = jabberProvider.getConnection();

        connection.addPacketListener(
                new MailboxIQListener(), new PacketTypeFilter(MailboxIQ.class));
        connection.addPacketListener(
                new NewMailNotificationListener(),
                new PacketTypeFilter(NewMailNotificationIQ.class));

        if(opSetPersPresence.getCurrentStatusMessage().equals(
                JabberStatusEnum.OFFLINE))
           return;

        //create a query with -1 values for newer-than-tid and
        //newer-than-time attributes
        MailboxQueryIQ mailboxQuery = new MailboxQueryIQ();

        if (logger.isTraceEnabled())
            logger.trace("sending mailNotification for acc: "
                    + jabberProvider.getAccountID().getAccountUniqueID());
        jabberProvider.getConnection().sendPacket(mailboxQuery);
    }

    /**
     * Creates an html description of the specified mailbox.
     *
     * @param mailboxIQ the mailboxIQ that we are to describe.
     *
     * @return an html description of <tt>mailboxIQ</tt>
     */
    private String createMailboxDescription(MailboxIQ mailboxIQ)
    {
        int threadCount = mailboxIQ.getThreadCount();

        String resourceHeaderKey = threadCount > 1
            ? "service.gui.NEW_GMAIL_MANY_HEADER"
            : "service.gui.NEW_GMAIL_HEADER";

        String resourceFooterKey = threadCount > 1
            ? "service.gui.NEW_GMAIL_MANY_FOOTER"
            : "service.gui.NEW_GMAIL_FOOTER";

        String newMailHeader = JabberActivator.getResources().getI18NString(
            resourceHeaderKey,
            new String[]
                {
                    jabberProvider.getAccountID()
                                .getService(),     //{0} - service name
                    mailboxIQ.getUrl(),            //{1} - inbox URI
                    Integer.toString( threadCount )//{2} - thread count
                });

        StringBuffer message = new StringBuffer(newMailHeader);

        //we now start an html table for the threads.
        message.append("<table width=100% cellpadding=2 cellspacing=0 ");
        message.append("border=0 bgcolor=#e8eef7>");

        Iterator<MailThreadInfo> threads = mailboxIQ.threads();

        String maxThreadsStr = (String)JabberActivator.getConfigurationService()
            .getProperty(PNAME_MAX_GMAIL_THREADS_PER_NOTIFICATION);

        int maxThreads = 5;

        try
        {
            if(maxThreadsStr != null)
                maxThreads = Integer.parseInt(maxThreadsStr);
        }
        catch (NumberFormatException e)
        {
            if (logger.isDebugEnabled())
                logger.debug("Failed to parse max threads count: "+maxThreads
                            +". Going for default.");
        }

        //print a maximum of MAX_THREADS
        for (int i = 0; i < maxThreads && threads.hasNext(); i++)
        {
            message.append(threads.next().createHtmlDescription());
        }
        message.append("</table><br/>");

        if(threadCount > maxThreads)
        {
            String messageFooter = JabberActivator.getResources().getI18NString(
                resourceFooterKey,
                new String[]
                {
                    mailboxIQ.getUrl(),            //{0} - inbox URI
                    Integer.toString(
                        threadCount - maxThreads )//{1} - thread count
                });
            message.append(messageFooter);
        }

        return message.toString();
    }

    /**
     * Receives incoming MailNotification Packets
     */
    private class MailboxIQListener
        implements PacketListener
    {
        /**
         * Handles incoming <tt>MailboxIQ</tt> packets.
         *
         * @param packet the IQ that we need to handle in case it is a
         * <tt>MailboxIQ</tt>.
         */
        public void processPacket(Packet packet)
        {
            if(packet != null &&  !(packet instanceof MailboxIQ))
                return;

            MailboxIQ mailboxIQ = (MailboxIQ) packet;

            if(mailboxIQ.getTotalMatched() < 1)
                return;

            //Get a reference to a dummy volatile contact
            Contact sourceContact = opSetPersPresence
                .findContactByID(jabberProvider.getAccountID().getService());

            if(sourceContact == null)
                sourceContact = opSetPersPresence.createVolatileContact(
                                jabberProvider.getAccountID().getService());

            lastReceivedMailboxResultTime = mailboxIQ.getResultTime();

            String newMail = createMailboxDescription(mailboxIQ);

            Message newMailMessage = new MessageJabberImpl(
                newMail, HTML_MIME_TYPE, DEFAULT_MIME_ENCODING, null);

            MessageReceivedEvent msgReceivedEvt = new MessageReceivedEvent(
                newMailMessage, sourceContact, new Date(),
                MessageReceivedEvent.SYSTEM_MESSAGE_RECEIVED);

            fireMessageEvent(msgReceivedEvt);
        }
    }

    /**
     * Receives incoming NewMailNotification Packets.
     */
    private class NewMailNotificationListener
        implements PacketListener
    {
        /**
         * Handles incoming <tt>NewMailNotificationIQ</tt> packets.
         *
         * @param packet the IQ that we need to handle in case it is a
         * <tt>NewMailNotificationIQ</tt>.
         */
        public void processPacket(Packet packet)
        {
            if(packet != null &&  !(packet instanceof NewMailNotificationIQ))
                return;

            //check whether we are still enabled.
            boolean enableGmailNotifications
                = jabberProvider
                    .getAccountID()
                        .getAccountPropertyBoolean(
                            "GMAIL_NOTIFICATIONS_ENABLED",
                            false);

            if (!enableGmailNotifications)
                return;

            if(opSetPersPresence.getCurrentStatusMessage()
                    .equals(JabberStatusEnum.OFFLINE))
                return;

            MailboxQueryIQ mailboxQueryIQ = new MailboxQueryIQ();

            if(lastReceivedMailboxResultTime != -1)
                mailboxQueryIQ.setNewerThanTime(
                                lastReceivedMailboxResultTime);

            if (logger.isTraceEnabled())
                logger.trace(
                "send mailNotification for acc: "
                + jabberProvider.getAccountID().getAccountUniqueID());

            jabberProvider.getConnection().sendPacket(mailboxQueryIQ);
        }
    }

    /**
     * Returns the inactivity timeout in milliseconds.
     *
     * @return The inactivity timeout in milliseconds. Or -1 if undefined
     */
    public long getInactivityTimeout()
    {
        return JID_INACTIVITY_TIMEOUT;
    }

    /**
     * Adds additional filters for incoming messages. To be able to skip some
     * messages.
     * @param filter to add
     */
    public void addMessageFilters(PacketFilter filter)
    {
        this.packetFilters.add(filter);
    }
}
