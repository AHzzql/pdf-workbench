package cn.edu.pdfworkbench.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Completes only after a bounded response body has been received. */
final class BoundedHttpBody implements HttpResponse.BodySubscriber<byte[]> {
  private final int limit;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> body = new CompletableFuture<>();
  private Flow.Subscription subscription;

  BoundedHttpBody(int limit) {
    if (limit < 1) throw new IllegalArgumentException("Invalid response body limit");
    this.limit = limit;
  }
  @Override public CompletionStage<byte[]> getBody() { return body; }
  @Override public void onSubscribe(Flow.Subscription value) {
    if (subscription != null) { value.cancel(); return; }
    subscription = value;
    value.request(1);
  }
  @Override public void onNext(List<ByteBuffer> buffers) {
    if (body.isDone()) return;
    for (var buffer : buffers) {
      int length = buffer.remaining();
      if (length > limit - bytes.size()) {
        subscription.cancel();
        body.completeExceptionally(new IOException("Response body exceeds limit"));
        return;
      }
      byte[] chunk = new byte[Math.min(length, 8192)];
      while (buffer.hasRemaining()) {
        int count = Math.min(buffer.remaining(), chunk.length);
        buffer.get(chunk, 0, count);
        bytes.write(chunk, 0, count);
      }
    }
    subscription.request(1);
  }
  @Override public void onError(Throwable error) { body.completeExceptionally(error); }
  @Override public void onComplete() { body.complete(bytes.toByteArray()); }
}
