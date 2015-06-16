package org.mitallast.queue.action;

import org.mitallast.queue.common.component.AbstractComponent;
import org.mitallast.queue.common.concurrent.Listener;
import org.mitallast.queue.common.concurrent.futures.Futures;
import org.mitallast.queue.common.concurrent.futures.ListenerSmartFuture;
import org.mitallast.queue.common.concurrent.futures.SmartFuture;
import org.mitallast.queue.common.settings.Settings;
import org.mitallast.queue.common.validation.ValidationException;
import org.mitallast.queue.transport.TransportController;

import java.lang.reflect.ParameterizedType;

public abstract class AbstractAction<Request extends ActionRequest, Response extends ActionResponse> extends AbstractComponent {

    @SuppressWarnings("all")
    public AbstractAction(Settings settings, TransportController controller) {
        super(settings);
        Class<Request> requestClass = (Class<Request>) ((ParameterizedType) getClass().getGenericSuperclass())
            .getActualTypeArguments()[0];
        controller.registerHandler(requestClass, this);
    }

    public SmartFuture<Response> execute(Request request) {
        ListenerSmartFuture<Response> listener = Futures.listenerFuture();
        execute(request, listener);
        return listener;
    }

    public void execute(Request request, Listener<Response> listener) {
        ValidationException validationException = request.validate().build();
        if (validationException != null) {
            listener.onFailure(validationException);
        } else {
            executeInternal(request, listener);
        }
    }

    protected abstract void executeInternal(Request request, Listener<Response> listener);
}
