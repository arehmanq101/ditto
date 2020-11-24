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
 package org.eclipse.ditto.model.base.exceptions;

 import java.net.URI;
 import java.text.MessageFormat;

 import javax.annotation.Nullable;
 import javax.annotation.concurrent.Immutable;
 import javax.annotation.concurrent.NotThreadSafe;

 import org.eclipse.ditto.json.JsonObject;
 import org.eclipse.ditto.model.base.common.HttpStatusCode;
 import org.eclipse.ditto.model.base.headers.DittoHeaders;
 import org.eclipse.ditto.model.base.json.JsonParsableException;

 /**
  * Thrown if a request with an unsupported data schema is made.
  *
  * @since 1.5.0
  */
 @Immutable
 @JsonParsableException(errorCode = CloudEventUnsupportedDataSchemaException.ERROR_CODE)
 public final class CloudEventUnsupportedDataSchemaException extends DittoRuntimeException {

     /**
      * Error code of this exception.
      */
     public static final String ERROR_CODE = "cloudevents.dataschema.unsupported";

     private static final String DEFAULT_MESSAGE = "The data schema is not supported.";
     private static final String MESSAGE_PATTERN = "The data schema <{0}> is not supported for this Resource.";
     private static final String DESCRIPTION = "Must start with 'ditto:'";

     private static final HttpStatusCode STATUS_CODE = HttpStatusCode.BAD_REQUEST;

     /**
      * Constructs a new {@code CloudEventUnsupportedDataSchemaException} object.
      *
      * @param dittoHeaders the headers with which this Exception should be reported back to the user.
      * @param message the detail message for later retrieval with {@link #getMessage()}.
      * @param description a description with further information about the exception.
      * @param cause the cause of the exception for later retrieval with {@link #getCause()}.
      * @param href a link to a resource which provides further information about the exception.
      * @throws NullPointerException if {@code dittoHeaders} is {@code null}.
      */
     private CloudEventUnsupportedDataSchemaException(final DittoHeaders dittoHeaders,
                                                      @Nullable final String message,
                                                      @Nullable final String description,
                                                      @Nullable final Throwable cause,
                                                      @Nullable final URI href) {
         super(ERROR_CODE, STATUS_CODE, dittoHeaders, message, description, cause, href);
     }

     /**
      * A mutable builder for a {@code CloudEventUnsupportedDataSchemaException} where the message contains detailed information
      * about the actual used data schema and the description information about data schemas are supported for the
      * requested resource.
      *
      * @param callersDataSchema the unsupported data schema used in the call.
      * @return the new CloudEventUnsupportedDataSchemaException.
      */
     public static DittoRuntimeExceptionBuilder<CloudEventUnsupportedDataSchemaException> withDetailedInformationBuilder(
             final String callersDataSchema) {

         final String msgPattern = MessageFormat.format(MESSAGE_PATTERN, callersDataSchema);
         return new Builder().message(msgPattern).description(DESCRIPTION);
     }

     /**
      * Constructs a new {@code UnsupportedDataSchemaException} object with the exception message extracted from the
      * given JSON object.
      *
      * @param jsonObject the JSON to read the {@link org.eclipse.ditto.model.base.exceptions.DittoRuntimeException.JsonFields#MESSAGE} field from.
      * @param dittoHeaders the headers of the command which resulted in this exception.
      * @return the new CloudEventUnsupportedDataSchemaException.
      * @throws NullPointerException if any argument is {@code null}.
      * @throws org.eclipse.ditto.json.JsonMissingFieldException if this JsonObject did not contain an error message.
      * @throws org.eclipse.ditto.json.JsonParseException if the passed in {@code jsonObject} was not in the expected
      * format.
      */
     public static CloudEventUnsupportedDataSchemaException fromJson(final JsonObject jsonObject, final DittoHeaders dittoHeaders) {
         return DittoRuntimeException.fromJson(jsonObject, dittoHeaders, new Builder());
     }

     @Override
    public DittoRuntimeException setDittoHeaders(final DittoHeaders dittoHeaders) {
         return new Builder()
                 .message(getMessage())
                 .description(getDescription().orElse(null))
                 .cause(getCause())
                 .href(getHref().orElse(null))
                 .dittoHeaders(dittoHeaders)
                 .build();
     }

     /**
      * A mutable builder with a fluent API for a {@link CloudEventUnsupportedDataSchemaException}.
      */
     @NotThreadSafe
     public static final class Builder extends DittoRuntimeExceptionBuilder<CloudEventUnsupportedDataSchemaException> {

         private Builder() {
             message(DEFAULT_MESSAGE);
         }

         @Override
         protected CloudEventUnsupportedDataSchemaException doBuild(final DittoHeaders dittoHeaders,
                                                                    @Nullable final String message,
                                                                    @Nullable final String description,
                                                                    @Nullable final Throwable cause,
                                                                    @Nullable final URI href) {

             return new CloudEventUnsupportedDataSchemaException(dittoHeaders, message, description, cause, href);
         }
     }

 }
