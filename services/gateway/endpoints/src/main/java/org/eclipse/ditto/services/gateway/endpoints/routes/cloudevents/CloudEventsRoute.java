/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.gateway.endpoints.routes.cloudevents;

import java.net.URI;
import java.text.MessageFormat;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.exceptions.CloudEventNotParsableException;
import org.eclipse.ditto.model.base.exceptions.CloudEventMissingPayloadException;
import org.eclipse.ditto.model.base.exceptions.CloudEventUnsupportedDataSchemaException;
import org.eclipse.ditto.model.base.exceptions.UnsupportedMediaTypeException;
import org.eclipse.ditto.model.base.headers.DittoHeaderDefinition;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.JsonSchemaVersion;
import org.eclipse.ditto.protocoladapter.DittoProtocolAdapter;
import org.eclipse.ditto.protocoladapter.HeaderTranslator;
import org.eclipse.ditto.protocoladapter.JsonifiableAdaptable;
import org.eclipse.ditto.protocoladapter.ProtocolFactory;
import org.eclipse.ditto.services.gateway.endpoints.actors.AbstractHttpRequestActor;
import org.eclipse.ditto.services.gateway.endpoints.routes.AbstractRoute;
import org.eclipse.ditto.services.gateway.util.config.endpoints.CommandConfig;
import org.eclipse.ditto.services.gateway.util.config.endpoints.HttpConfig;
import org.eclipse.ditto.services.utils.akka.logging.DittoLoggerFactory;
import org.eclipse.ditto.services.utils.akka.logging.ThreadSafeDittoLogger;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.commands.base.CommandNotSupportedException;

import com.eclipsesource.json.Json;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Status;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.RequestContext;
import akka.http.javadsl.server.Route;
import akka.stream.javadsl.Sink;
import akka.util.ByteString;
import io.cloudevents.CloudEvent;
import io.cloudevents.CloudEventData;
import io.cloudevents.core.message.MessageReader;
import io.cloudevents.http.HttpMessageFactory;
import io.cloudevents.rw.CloudEventRWException;

/**
 * Builder for creating Akka HTTP route for {@code /cloudevents}.
 */
public final class CloudEventsRoute extends AbstractRoute {

    private static final ThreadSafeDittoLogger LOGGER =
            DittoLoggerFactory.getThreadSafeLogger(CloudEventsRoute.class);

    /**
     * Public endpoint of cloud events.
     */
    public static final String PATH_CLOUDEVENTS = "cloudevents";

    // use empty header translator to pass along the ditto-auth-context information
    private static final DittoProtocolAdapter PROTOCOL_ADAPTER = DittoProtocolAdapter.of(HeaderTranslator.empty());

    /**
     * Constructs the cloud events route builder.
     *
     * @param proxyActor an actor selection of the actor handling delegating to persistence.
     * @param actorSystem the ActorSystem to use.
     * @param httpConfig the configuration settings of the Gateway service's HTTP endpoint.
     * @param commandConfig the configuration settings for incoming commands (via HTTP requests) in the gateway.
     * @param headerTranslator translates headers from external sources or to external sources.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public CloudEventsRoute(
            final ActorRef proxyActor,
            final ActorSystem actorSystem,
            final HttpConfig httpConfig,
            final CommandConfig commandConfig,
            final HeaderTranslator headerTranslator
    ) {
        super(proxyActor, actorSystem, httpConfig, commandConfig, headerTranslator);
    }


    /**
     * Builds the {@code /cloudevents} route.
     *
     * @return the {@code /cloudevents} route.
     */
    public Route buildCloudEventsRoute(final RequestContext ctx, final DittoHeaders dittoHeaders) {
        return path(PATH_CLOUDEVENTS, () -> // /cloudevents
                post(() -> // POST
                        acceptCloudEvent(ctx, dittoHeaders)
                )
        );
    }

    protected Route acceptCloudEvent(final RequestContext ctx, final DittoHeaders dittoHeaders) {

        return extractDataBytes(payloadSource -> {

            final CompletableFuture<HttpResponse> httpResponseFuture = new CompletableFuture<>();

            runWithSupervisionStrategy(payloadSource
                    // collect the binary payload
                    .fold(ByteString.emptyByteString(), ByteString::concat)
                    // map the payload to a cloud event
                    .map(payload -> toCloudEvent(ctx, dittoHeaders, payload))
                    // validate the cloud event
                    .map(cloudEvent -> validateCloudEvent(cloudEvent, ctx, dittoHeaders))
                    // process the event
                    .map(cloudEvent -> {
                        try {
                            // DON'T replace this try-catch by .recover: The supervising strategy is called before recovery!
                            final Optional<Signal<?>> optionalSignal = jsonToDittoSignal(cloudEvent.getData(), dittoHeaders);
                            if (optionalSignal.isEmpty()) {
                                return new Status.Failure(CloudEventMissingPayloadException
                                        .withDetailedInformationBuilder()
                                        .dittoHeaders(dittoHeaders)
                                        .build());
                            }

                            final Signal<?> signal = optionalSignal.get();
                            final JsonSchemaVersion schemaVersion = signal.getImplementedSchemaVersion();
                            return signal.implementsSchemaVersion(schemaVersion) ? signal
                                    : CommandNotSupportedException.newBuilder(schemaVersion.toInt())
                                    .dittoHeaders(dittoHeaders)
                                    .build();
                        } catch (final Exception e) {
                            return new Status.Failure(e);
                        }
                    })
                    .to(Sink.actorRef(createHttpPerRequestActor(ctx, httpResponseFuture),
                            AbstractHttpRequestActor.COMPLETE_MESSAGE))
            );

            // return with future

            return completeWithFuture(httpResponseFuture);

        });

    }

    /**
     * Convert the request and payload to a cloud event.
     *
     * @param ctx The request context.
     * @param payload The binary payload, this may contain the cloud event payload, or the fully encoded cloud
     *         event structure.
     * @return The cloud event
     */
    private CloudEvent toCloudEvent(final RequestContext ctx, final DittoHeaders dittoHeaders, final ByteString payload) {

        if (LOGGER.isTraceEnabled()) {

            final StringBuilder headers = new StringBuilder("Raw HTTP Headers:");
            ctx.getRequest().getHeaders()
                    .forEach(header -> headers
                            .append("\n\t")
                            .append(header.name())
                            .append(" = ")
                            .append(header.value()));

            LOGGER
                    .withCorrelationId(dittoHeaders)
                    .trace(headers.toString());

            LOGGER
                    .withCorrelationId(dittoHeaders)
                    .trace("Ditto: {}", dittoHeaders);
        }

        // create a reader for the message
        final MessageReader reader = HttpMessageFactory.createReader(acceptor -> {

            // NOTE: this acceptor may be run multiple times by the message reader

            // record if we saw the content type header
            final AtomicBoolean sawContentType = new AtomicBoolean();
            // consume the HTTP request headers
            ctx.getRequest().getHeaders().forEach(header -> {
                if (header.lowercaseName().equals(DittoHeaderDefinition.CONTENT_TYPE.getKey())) {
                    sawContentType.set(true);
                }
                acceptor.accept(header.name(), header.value());
            });

            if (!sawContentType.get()) {
                // we didn't see the content type in the header, so extract it from akka's request
                acceptor.accept(DittoHeaderDefinition.CONTENT_TYPE.getKey(), ctx.getRequest().entity().getContentType().mediaType().toString());
            }
        }, payload.toArray());

        try {
            return reader.toEvent();
        } catch (final CloudEventRWException | IllegalStateException e) {
            throw CloudEventNotParsableException.withDetailedInformationBuilder(e.getMessage())
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    private CloudEvent validateCloudEvent(final CloudEvent cloudEvent, final RequestContext ctx, final DittoHeaders dittoHeaders) {

        if (cloudEvent.getData() == null) {
            throw CloudEventMissingPayloadException
                    .withDetailedInformationBuilder()
                    .dittoHeaders(dittoHeaders)
                    .build();
        }

        LOGGER
                .withCorrelationId(dittoHeaders)
                .debug("Cloud event: {}", cloudEvent);

        ensureDataContentType(cloudEvent.getDataContentType(), ctx, dittoHeaders);
        ensureDataSchema(cloudEvent.getDataSchema(), ctx, dittoHeaders);

        return cloudEvent;
    }

    private Optional<Signal<?>> jsonToDittoSignal(@Nullable final CloudEventData data, final DittoHeaders dittoHeaders) {
        if (data == null) {
            return Optional.empty();
        }
        final byte[] payload = data.toBytes();
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }

        JsonObject jsonObject = JsonObject.of(payload);
        LOGGER
                .withCorrelationId(dittoHeaders)
                .debug("JSON: {}", jsonObject);

        final JsonObject headers = jsonObject.getField("headers")

                // get value
                .map(JsonField::getValue)

                // convert to JsonObject, or "empty"
                .flatMap(value -> {
                    if (value.isObject()) {
                        return Optional.of(value.asObject());
                     } else {
                        return Optional.empty();
                    }
                })

                // get existing or new
                .orElseGet(JsonObject::empty)

                // never require a response
                .setValue("response-required", false)
                .setValue("requested-acks", JsonArray.newBuilder().add("twin-persisted").build());

        jsonObject = jsonObject.setValue("headers", headers);

        LOGGER
                .withCorrelationId(dittoHeaders)
                .debug("Updated JSON: {}", jsonObject);

        final JsonifiableAdaptable jsonifiableAdaptable = ProtocolFactory.jsonifiableAdaptableFromJson(jsonObject);
        return Optional.of(PROTOCOL_ADAPTER
                .fromAdaptable(jsonifiableAdaptable)
                .setDittoHeaders(dittoHeaders));
    }

    private void ensureDataContentType(@Nullable final String dataContentType,
                                       final RequestContext ctx,
                                       final DittoHeaders dittoHeaders) {

        if (dataContentType == null || !mediaTypeJsonWithFallbacks.contains(dataContentType)) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.withCorrelationId(dittoHeaders)
                        .info("Request rejected: unsupported data-content-type: <{}>  request: <{}>", dataContentType,
                                requestToLogString(ctx.getRequest()));
            }
            throw UnsupportedMediaTypeException
                    .withDetailedInformationBuilder(dataContentType != null ? dataContentType : "<none>", mediaTypeJsonWithFallbacks)
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    /**
     * Ensure that the data schema starts with {@code ditto:}.
     *
     * @param dataSchema The schema to verify
     * @param ctx The request context.
     * @param dittoHeaders The ditto headers.
     */
    private void ensureDataSchema(@Nullable final URI dataSchema,
                                  final RequestContext ctx,
                                  final DittoHeaders dittoHeaders) {

        if (dataSchema == null || !dataSchema.getScheme().equals("ditto")) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.withCorrelationId(dittoHeaders)
                        .info("Request rejected: unsupported data-schema: <{}>  request: <{}>", dataSchema,
                                requestToLogString(ctx.getRequest()));
            }
            throw CloudEventUnsupportedDataSchemaException
                    .withDetailedInformationBuilder(dataSchema != null ? dataSchema.toString() : "<none>")
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    private static String requestToLogString(final HttpRequest request) {
        return MessageFormat.format("{0} {1} {2}",
                request.getUri().getHost().address(),
                request.method().value(),
                request.getUri().getPathString());
    }

}
