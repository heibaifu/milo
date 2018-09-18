/*
 * Copyright (c) 2018 Kevin Herron
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *   http://www.eclipse.org/org/documents/edl-v10.html.
 */

package org.eclipse.milo.opcua.stack.server.transport.http;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Objects;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedHttpsCertificateBuilder;
import org.eclipse.milo.opcua.stack.server.UaStackServer;
import org.eclipse.milo.opcua.stack.server.transport.RateLimitingHandler;
import org.eclipse.milo.opcua.stack.server.transport.uasc.UascServerHelloHandler;
import org.eclipse.milo.opcua.stack.server.transport.websocket.OpcServerWebSocketFrameHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.milo.opcua.stack.server.transport.SocketServerManager.SocketServer.ServerLookup;

public class OpcServerHttpChannelInitializer extends ChannelInitializer<SocketChannel> {

    public static final String WS_PROTOCOL_BINARY = "opcua+uacp";
    public static final String WS_PROTOCOL_JSON = "opcua+uajson";

    private final ServerLookup serverLookup;

    public OpcServerHttpChannelInitializer(ServerLookup serverLookup) {
        this.serverLookup = serverLookup;
    }

    @Override
    protected void initChannel(SocketChannel channel) throws Exception {
        // TODO how is the certificate for HTTPS configured/provided?
        // TODO configure private key and certificate
        KeyPair keyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(2048);

        X509Certificate serverHttpsCertificate = new SelfSignedHttpsCertificateBuilder(keyPair)
            .setCommonName("localhost")
            .build();

        PrivateKey privateKey = keyPair.getPrivate();

        SslContext sslContext = SslContextBuilder
            .forServer(privateKey, serverHttpsCertificate)
            .clientAuth(ClientAuth.NONE)
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build();

        channel.pipeline().addLast(RateLimitingHandler.getInstance());
        channel.pipeline().addLast(sslContext.newHandler(channel.alloc()));
        channel.pipeline().addLast(new LoggingHandler(LogLevel.TRACE));
        channel.pipeline().addLast(new HttpServerCodec());

        // TODO configure maxContentLength based on MaxRequestSize?
        channel.pipeline().addLast(new HttpObjectAggregator(Integer.MAX_VALUE));
        channel.pipeline().addLast(new OpcHttpTransportInterceptor(serverLookup));
    }

    private static class OpcHttpTransportInterceptor extends SimpleChannelInboundHandler<FullHttpRequest> {

        private final Logger logger = LoggerFactory.getLogger(getClass());

        private final ServerLookup serverLookup;

        public OpcHttpTransportInterceptor(ServerLookup serverLookup) {
            this.serverLookup = serverLookup;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest httpRequest) throws Exception {
            // TODO server lookup might have to be by path instead of full endpoint URL?

            String host = httpRequest.headers().get(HttpHeaderNames.HOST);
            String uri = httpRequest.uri();

            logger.info("host={} uri={}", host, uri);

            UaStackServer stackServer = serverLookup.getServer(uri).orElseThrow(
                () -> new UaException(
                    StatusCodes.Bad_TcpEndpointUrlInvalid,
                    "unrecognized endpoint uri: " + uri)
            );

            if (Objects.equals(httpRequest.method(), HttpMethod.GET) &&
                "websocket".equalsIgnoreCase(httpRequest.headers().get(HttpHeaderValues.UPGRADE))) {

                logger.info("intercepted WebSocket upgrade");

                ctx.channel().pipeline().remove(this);

                ctx.channel().pipeline().addLast(new WebSocketServerCompressionHandler());

                // TODO configure webSocketPath based on path component of endpoint URL?
                ctx.channel().pipeline().addLast(new WebSocketServerProtocolHandler(
                    "/ws",
                    String.format("%s, %s", WS_PROTOCOL_BINARY, WS_PROTOCOL_JSON),
                    true
                ));

                ctx.channel().pipeline().addLast(new OpcServerWebSocketFrameHandler());
                ctx.channel().pipeline().addLast(new UascServerHelloHandler(serverLookup));

                httpRequest.retain();
                ctx.executor().execute(() -> ctx.fireChannelRead(httpRequest));
            } else if (Objects.equals(httpRequest.method(), HttpMethod.POST)) {
                logger.info("intercepted HTTP POST");

                ctx.channel().pipeline().remove(this);
                ctx.channel().pipeline().addLast(new OpcServerHttpRequestHandler(stackServer));

                httpRequest.retain();
                ctx.executor().execute(() -> ctx.pipeline().fireChannelRead(httpRequest));
            } else {
                HttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_REQUEST
                );

                ctx.channel().writeAndFlush(response)
                    .addListener(future -> ctx.close());
            }
        }
    }

}