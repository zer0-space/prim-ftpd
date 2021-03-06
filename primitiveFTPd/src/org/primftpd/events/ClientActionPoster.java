package org.primftpd.events;

public interface ClientActionPoster {

    public void postClientAction(
            ClientActionEvent.Storage storage,
            ClientActionEvent.ClientAction clientAction,
            String clientIp,
            String path);
}
