package org.mitallast.queue.rest.action.queue.transactional;

import com.google.inject.Inject;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.mitallast.queue.action.queue.transactional.delete.TransactionDeleteRequest;
import org.mitallast.queue.client.Client;
import org.mitallast.queue.common.UUIDs;
import org.mitallast.queue.common.settings.Settings;
import org.mitallast.queue.rest.BaseRestHandler;
import org.mitallast.queue.rest.RestController;
import org.mitallast.queue.rest.RestRequest;
import org.mitallast.queue.rest.RestSession;
import org.mitallast.queue.rest.response.StatusRestResponse;

public class RestTransactionDeleteAction extends BaseRestHandler {

    @Inject
    public RestTransactionDeleteAction(Settings settings, Client client, RestController controller) {
        super(settings, client);
        controller.registerHandler(HttpMethod.DELETE, "/{queue}/{transaction}/message", this);
        controller.registerHandler(HttpMethod.DELETE, "/{queue}/{transaction}/message/{uuid}", this);
    }

    @Override
    public void handleRequest(final RestRequest request, final RestSession session) {
        TransactionDeleteRequest.Builder builder = TransactionDeleteRequest.builder()
            .setQueue(request.param("queue").toString())
            .setTransactionUUID(UUIDs.fromString(request.param("transaction")));

        CharSequence uuid = request.param("uuid");
        if (uuid != null) {
            builder.setMessageUUID(UUIDs.fromString(uuid));
        }

        client.queue().transactional().deleteRequest(builder.build()).whenComplete((response, error) -> {
            if (error == null) {
                session.sendResponse(new StatusRestResponse(HttpResponseStatus.ACCEPTED));
            } else {
                session.sendResponse(error);
            }
        });
    }
}