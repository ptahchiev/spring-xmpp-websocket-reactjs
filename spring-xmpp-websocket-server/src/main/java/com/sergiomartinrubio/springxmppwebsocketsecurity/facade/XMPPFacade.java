package com.sergiomartinrubio.springxmppwebsocketsecurity.facade;

import com.sergiomartinrubio.springxmppwebsocketsecurity.exception.XMPPGenericException;
import com.sergiomartinrubio.springxmppwebsocketsecurity.model.Account;
import com.sergiomartinrubio.springxmppwebsocketsecurity.model.TextMessage;
import com.sergiomartinrubio.springxmppwebsocketsecurity.service.AccountService;
import com.sergiomartinrubio.springxmppwebsocketsecurity.utils.BCryptUtils;
import com.sergiomartinrubio.springxmppwebsocketsecurity.websocket.utils.WebSocketTextMessageHelper;
import com.sergiomartinrubio.springxmppwebsocketsecurity.xmpp.XMPPClient;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.search.ReportedData;
import org.jivesoftware.smackx.search.UserSearchManager;
import org.jivesoftware.smackx.xdata.form.Form;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import javax.websocket.Session;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.sergiomartinrubio.springxmppwebsocketsecurity.model.MessageType.ERROR;
import static com.sergiomartinrubio.springxmppwebsocketsecurity.model.MessageType.FORBIDDEN;
import static com.sergiomartinrubio.springxmppwebsocketsecurity.model.MessageType.JOIN_SUCCESS;

@Slf4j
@Component
@RequiredArgsConstructor
public class XMPPFacade {

    private static final Map<Session, XMPPTCPConnection> CONNECTIONS = new HashMap<>();

    private final AccountService accountService;
    private final WebSocketTextMessageHelper webSocketTextMessageHelper;
    private final XMPPClient xmppClient;

    public void startSession(Session session, String username, String password) {
        Optional<Account> account = accountService.getAccount(username);

        if (account.isPresent() && !BCryptUtils.isMatch(password, account.get().getPassword())) {
            log.warn("Invalid password for user {}.", username);
            webSocketTextMessageHelper.send(session, TextMessage.builder().messageType(FORBIDDEN).build());
            return;
        }

        Optional<XMPPTCPConnection> connection = xmppClient.connect(username, password);

        if (connection.isEmpty()) {
            webSocketTextMessageHelper.send(session, TextMessage.builder().messageType(ERROR).build());
            return;
        }

        try {
            if (account.isEmpty()) {
                xmppClient.createAccount(connection.get(), username, password);
            }
            xmppClient.login(connection.get());
        } catch (XMPPGenericException e) {
            log.error("XMPP error. Disconnecting and removing session...", e);
            xmppClient.disconnect(connection.get());
            webSocketTextMessageHelper.send(session, TextMessage.builder().messageType(ERROR).build());
            CONNECTIONS.remove(session);
            return;
        }

        CONNECTIONS.put(session, connection.get());
        log.info("Session was stored.");

        xmppClient.addIncomingMessageListener(connection.get(), session);

        webSocketTextMessageHelper.send(session, TextMessage.builder().to(username).messageType(JOIN_SUCCESS).build());
    }

    @SneakyThrows
    public void sendMessage(String message, String to, Session session) {
        XMPPTCPConnection connection = CONNECTIONS.get(session);

        if (connection == null) {
            return;
        }



//        Roster roster = Roster.getInstanceFor(connection);
//
//        if (!roster.isLoaded())
//            roster.reloadAndWait();
//
//        Collection<RosterEntry> entries = roster.getEntries();
//        for (RosterEntry entry : entries) {
//            System.out.println(entry);
//        }

        try {
            xmppClient.sendMessage(connection, message, to);
        } catch (XMPPGenericException e) {
            log.error("XMPP error. Disconnecting and removing session...", e);
            xmppClient.disconnect(connection);
            webSocketTextMessageHelper.send(session, TextMessage.builder().messageType(ERROR).build());
            CONNECTIONS.remove(session);
        }
    }

    public void disconnect(Session session) {
        XMPPTCPConnection connection = CONNECTIONS.get(session);

        if (connection == null) {
            return;
        }

        try {
            xmppClient.sendStanza(connection, Presence.Type.unavailable);
        } catch (XMPPGenericException e) {
            log.error("XMPP error.", e);
            webSocketTextMessageHelper.send(session, TextMessage.builder().messageType(ERROR).build());
        }

        xmppClient.disconnect(connection);
        CONNECTIONS.remove(session);
    }
}
