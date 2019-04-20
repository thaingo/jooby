/**
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    Copyright 2014 Edgar Espina
 */
package io.jooby.internal.netty;

import io.jooby.Body;
import io.jooby.ByteRange;
import io.jooby.Context;
import io.jooby.FileUpload;
import io.jooby.Formdata;
import io.jooby.MediaType;
import io.jooby.Multipart;
import io.jooby.QueryString;
import io.jooby.Route;
import io.jooby.Router;
import io.jooby.Sender;
import io.jooby.Server;
import io.jooby.StatusCode;
import io.jooby.Throwing;
import io.jooby.Value;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import io.netty.handler.stream.ChunkedNioStream;
import io.netty.handler.stream.ChunkedStream;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCounted;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import static io.netty.buffer.Unpooled.copiedBuffer;
import static io.netty.buffer.Unpooled.wrappedBuffer;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.RANGE;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import static io.netty.handler.codec.http.HttpUtil.isKeepAlive;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;
import static java.nio.charset.StandardCharsets.UTF_8;

public class NettyContext implements Context, ChannelFutureListener {

  private static final HttpHeaders NO_TRAILING = new DefaultHttpHeaders(false);
  final DefaultHttpHeaders setHeaders = new DefaultHttpHeaders(false);
  private final int bufferSize;
  InterfaceHttpPostRequestDecoder decoder;
  private Router router;
  private Route route;
  private ChannelHandlerContext ctx;
  private HttpRequest req;
  private String path;
  private HttpResponseStatus status = HttpResponseStatus.OK;
  private boolean responseStarted;
  private QueryString query;
  private Formdata form;
  private Multipart multipart;
  private List<FileUpload> files;
  private Value headers;
  private Map<String, String> pathMap = Collections.EMPTY_MAP;
  private MediaType responseType;
  private Map<String, Object> attributes = new HashMap<>();
  private long contentLength = -1;
  private boolean needsFlush;

  public NettyContext(ChannelHandlerContext ctx, HttpRequest req, Router router, String path,
      int bufferSize) {
    this.path = path;
    this.ctx = ctx;
    this.req = req;
    this.router = router;
    this.bufferSize = bufferSize;
  }

  @Nonnull @Override public Router getRouter() {
    return router;
  }

  /* **********************************************************************************************
   * Request methods:
   * **********************************************************************************************
   */

  @Nonnull @Override public Map<String, Object> getAttributes() {
    return attributes;
  }

  @Nonnull @Override public String getMethod() {
    return req.method().asciiName().toUpperCase().toString();
  }

  @Nonnull @Override public Route getRoute() {
    return route;
  }

  @Nonnull @Override public Context setRoute(@Nonnull Route route) {
    this.route = route;
    return this;
  }

  @Nonnull @Override public final String pathString() {
    return path;
  }

  @Nonnull @Override public Map<String, String> pathMap() {
    return pathMap;
  }

  @Nonnull @Override public Context setPathMap(@Nonnull Map<String, String> pathMap) {
    this.pathMap = pathMap;
    return this;
  }

  @Override public final boolean isInIoThread() {
    return ctx.channel().eventLoop().inEventLoop();
  }

  @Nonnull @Override public Context dispatch(@Nonnull Runnable action) {
    return dispatch(router.getWorker(), action);
  }

  @Override public Context dispatch(Executor executor, Runnable action) {
    executor.execute(action);
    return this;
  }

  @Nonnull @Override public Context detach(@Nonnull Runnable action) {
    action.run();
    return this;
  }

  @Nonnull @Override public QueryString query() {
    if (query == null) {
      String uri = req.uri();
      int q = uri.indexOf('?');
      query = QueryString.create(q >= 0 ? uri.substring(q + 1) : null);
    }
    return query;
  }

  @Nonnull @Override public Formdata form() {
    if (form == null) {
      form = Formdata.create();
      decodeForm(req, form);
    }
    return form;
  }

  @Nonnull @Override public Multipart multipart() {
    if (multipart == null) {
      multipart = Multipart.create();
      form = multipart;
      decodeForm(req, multipart);
    }
    return multipart;
  }

  @Nonnull @Override public Value header(@Nonnull String name) {
    return Value.create(name, req.headers().getAll(name));
  }

  @Nonnull @Override public String getRemoteAddress() {
    InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
    return remoteAddress.getAddress().getHostAddress();
  }

  @Nonnull @Override public String getProtocol() {
    return req.protocolVersion().text();
  }

  @Nonnull @Override public String getScheme() {
    // TODO: review if we add websocket or https
    return "http";
  }

  @Nonnull @Override public Value headers() {
    if (headers == null) {
      Map<String, Collection<String>> headerMap = new LinkedHashMap<>();
      HttpHeaders headers = req.headers();
      Set<String> names = headers.names();
      for (String name : names) {
        headerMap.put(name, headers.getAll(name));
      }
      this.headers = Value.hash(headerMap);
    }
    return headers;
  }

  @Nonnull @Override public Body body() {
    if (decoder != null && decoder.hasNext()) {
      return new NettyBody((HttpData) decoder.next(), HttpUtil.getContentLength(req, -1L));
    }
    return Body.empty();
  }

  /* **********************************************************************************************
   * Response methods:
   * **********************************************************************************************
   */

  @Nonnull @Override public StatusCode getResponseCode() {
    return StatusCode.valueOf(this.status.code());
  }

  @Nonnull @Override public Context setResponseCode(int statusCode) {
    this.status = HttpResponseStatus.valueOf(statusCode);
    return this;
  }

  @Nonnull @Override public Context setResponseHeader(@Nonnull String name, @Nonnull String value) {
    setHeaders.set(name, value);
    return this;
  }

  @Nonnull @Override public MediaType getResponseType() {
    return responseType == null ? MediaType.text : responseType;
  }

  @Nonnull @Override public Context setDefaultResponseType(@Nonnull MediaType contentType) {
    if (responseType == null) {
      setResponseType(contentType, contentType.getCharset());
    }
    return this;
  }

  @Override public final Context setResponseType(MediaType contentType, Charset charset) {
    this.responseType = contentType;
    setHeaders.set(CONTENT_TYPE, contentType.toContentTypeHeader(charset));
    return this;
  }

  @Nonnull @Override public Context setResponseType(@Nonnull String contentType) {
    this.responseType = MediaType.valueOf(contentType);
    setHeaders.set(CONTENT_TYPE, contentType);
    return this;
  }

  @Nonnull @Override public Context setResponseLength(long length) {
    contentLength = length;
    setHeaders.set(CONTENT_LENGTH, length);
    return this;
  }

  @Nonnull @Override public PrintWriter responseWriter(MediaType type, Charset charset) {
    setResponseType(type, charset);

    return new PrintWriter(new NettyWriter(newOutputStream(), charset));
  }

  @Nonnull @Override public Sender responseSender() {
    responseStarted = true;
    prepareChunked();
    ctx.write(new DefaultHttpResponse(req.protocolVersion(), status, setHeaders));
    return new NettySender(this, ctx);
  }

  @Nonnull @Override public OutputStream responseStream() {
    return newOutputStream();
  }

  @Nonnull @Override public Context send(@Nonnull String data) {
    return send(copiedBuffer(data, UTF_8));
  }

  @Override public final Context send(String data, Charset charset) {
    return send(copiedBuffer(data, charset));
  }

  @Override public final Context send(byte[] data) {
    return send(wrappedBuffer(data));
  }

  @Override public final Context send(ByteBuffer data) {
    if (data.hasArray()) {
      return send(wrappedBuffer(data.array()));
    } else {
      return send(wrappedBuffer(data));
    }
  }

  @Nonnull @Override public Context send(@Nonnull ByteBuf data) {
    responseStarted = true;
    setHeaders.set(CONTENT_LENGTH, data.readableBytes());
    DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status,
        data, setHeaders, NO_TRAILING);
    if (ctx.channel().eventLoop().inEventLoop()) {
      needsFlush = true;
      ctx.write(response).addListener(this);
    } else {
      ctx.writeAndFlush(response).addListener(this);
    }
    return this;
  }

  public void flush() {
    if (needsFlush) {
      needsFlush = false;
      ctx.flush();
    }
  }

  @Nonnull @Override public Context send(@Nonnull ReadableByteChannel channel) {
    prepareChunked();
    DefaultHttpResponse rsp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status, setHeaders);
    responseStarted = true;
    int bufferSize = contentLength > 0 ? (int) contentLength : this.bufferSize;
    ctx.channel().eventLoop().execute(() -> {
      // Headers
      ctx.write(rsp, ctx.voidPromise());
      // Body
      ctx.write(new ChunkedNioStream(channel, bufferSize), ctx.voidPromise());
      // Finish
      ctx.writeAndFlush(EMPTY_LAST_CONTENT).addListener(this);
    });
    return this;
  }

  @Nonnull @Override public Context send(@Nonnull InputStream in) {
    if (in instanceof FileInputStream) {
      // use channel
      return send(((FileInputStream) in).getChannel());
    }
    try {
      prepareChunked();
      long len = responseLength();
      ByteRange range = ByteRange.parse(req.headers().get(RANGE), len)
          .apply(this);
      ChunkedStream chunkedStream = new ChunkedStream(range.apply(in), bufferSize);

      DefaultHttpResponse rsp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status, setHeaders);
      responseStarted = true;
      ctx.channel().eventLoop().execute(() -> {
        // Headers
        ctx.write(rsp, ctx.voidPromise());
        // Body
        ctx.write(chunkedStream, ctx.voidPromise());
        // Finish
        ctx.writeAndFlush(EMPTY_LAST_CONTENT).addListener(this);
      });
      return this;
    } catch (Exception x) {
      throw Throwing.sneakyThrow(x);
    }
  }

  @Nonnull @Override public Context send(@Nonnull FileChannel file) {
    try {
      long len = file.size();
      setHeaders.set(CONTENT_LENGTH, len);

      ByteRange range = ByteRange.parse(req.headers().get(RANGE), len)
          .apply(this);

      DefaultHttpResponse rsp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status, setHeaders);
      responseStarted = true;
      ctx.channel().eventLoop().execute(() -> {
        // Headers
        ctx.write(rsp, ctx.voidPromise());
        // Body
        ctx.write(new DefaultFileRegion(file, range.getStart(), range.getEnd()), ctx.voidPromise());
        // Finish
        ctx.writeAndFlush(EMPTY_LAST_CONTENT).addListener(this);
      });
    } catch (IOException x) {
      throw Throwing.sneakyThrow(x);
    }
    return this;
  }

  @Override public boolean isResponseStarted() {
    return responseStarted;
  }

  @Nonnull @Override public Context send(StatusCode statusCode) {
    responseStarted = true;
    setHeaders.set(CONTENT_LENGTH, 0);
    DefaultFullHttpResponse rsp = new DefaultFullHttpResponse(HTTP_1_1,
        HttpResponseStatus.valueOf(statusCode.value()), Unpooled.EMPTY_BUFFER, setHeaders,
        NO_TRAILING);
    ctx.writeAndFlush(rsp).addListener(this);
    return this;
  }

  @Override public void operationComplete(ChannelFuture future) {
    boolean keepAlive = isKeepAlive(req);
    try {
      destroy(future.cause());
    } finally {
      if (!keepAlive) {
        future.channel().close();
      }
    }
  }

  private NettyOutputStream newOutputStream() {
    prepareChunked();
    return new NettyOutputStream(ctx, bufferSize,
        new DefaultHttpResponse(req.protocolVersion(), status, setHeaders), this);
  }

  void destroy(Throwable cause) {
    Logger log = router.getLog();
    if (cause != null) {
      if (Server.connectionLost(cause)) {
        log.debug("exception found while sending response {} {}", getMethod(), pathString(), cause);
      } else {
        log.error("exception found while sending response {} {}", getMethod(), pathString(), cause);
      }
    }
    if (files != null) {
      for (FileUpload file : files) {
        try {
          file.destroy();
        } catch (Exception x) {
          log.debug("file upload destroy resulted in exception", x);
        }
      }
      files = null;
    }
    if (decoder != null) {
      try {
        decoder.destroy();
      } catch (Exception x) {
        log.debug("body decoder destroy resulted in exception", x);
      }
      decoder = null;
    }
    release(req);
    this.route = null;
    this.req = null;
    this.router = null;
  }

  private FileUpload register(FileUpload upload) {
    if (this.files == null) {
      this.files = new ArrayList<>();
    }
    this.files.add(upload);
    return upload;
  }

  private void decodeForm(HttpRequest req, Formdata form) {
    try {
      while (decoder.hasNext()) {
        HttpData next = (HttpData) decoder.next();
        if (next.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
          form.put(next.getName(),
              register(new NettyFileUpload(router.getTmpdir(), next.getName(),
                  (io.netty.handler.codec.http.multipart.FileUpload) next)));
        } else {
          form.put(next.getName(), next.getString(UTF_8));
        }
      }
    } catch (HttpPostRequestDecoder.EndOfDataDecoderException x) {
      // ignore, silly netty
    } catch (Exception x) {
      throw Throwing.sneakyThrow(x);
    } finally {
      release(req);
    }
  }

  private static void release(HttpRequest req) {
    if (req instanceof ReferenceCounted) {
      ReferenceCounted ref = (ReferenceCounted) req;
      if (ref.refCnt() > 0) {
        ref.release();
      }
    }
  }

  private long responseLength() {
    String len = setHeaders.get(CONTENT_LENGTH);
    return len == null ? -1 : Long.parseLong(len);
  }

  private void prepareChunked() {
    // remove flusher, doesn't play well with streaming/chunked responses
    ChannelPipeline pipeline = ctx.pipeline();
    if (pipeline.get("chunker") == null) {
      pipeline.addAfter("encoder", "chunker", new ChunkedWriteHandler());
    }
    if (!setHeaders.contains(CONTENT_LENGTH)) {
      setHeaders.set(TRANSFER_ENCODING, CHUNKED);
    }
  }
}
