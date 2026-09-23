package org.springframework.web.servlet.mvc.method.annotation;

import org.springframework.http.MediaType;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/** Test-only: attaches an in-memory handler to a ResponseBodyEmitter, as Spring MVC does for a live request. */
public final class EmitterCapture {
    private EmitterCapture() {
    }

    public static CountDownLatch attach(ResponseBodyEmitter emitter, StringBuffer out) throws java.io.IOException {
        CountDownLatch done = new CountDownLatch(1);
        Runnable[] completion = {() -> { }};
        emitter.initialize(new ResponseBodyEmitter.Handler() {
            public void send(Object data, MediaType mediaType) { out.append(data); }
            public void send(Set<ResponseBodyEmitter.DataWithMediaType> items) { for (var i : items) out.append(i.getData()); }
            public void complete() { completion[0].run(); done.countDown(); }
            public void completeWithError(Throwable failure) { done.countDown(); }
            public void onTimeout(Runnable callback) { }
            public void onError(Consumer<Throwable> callback) { }
            public void onCompletion(Runnable callback) { completion[0] = callback; }
        });
        return done;
    }
}
