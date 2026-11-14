/*
 * SoulFire
 * Copyright (C) 2026  AlexProgrammerDE
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.soulfiremc.server.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.AnnotationsProto;
import com.google.api.FieldBehavior;
import com.google.api.FieldBehaviorProto;
import com.google.api.HttpRule;
import com.google.protobuf.Descriptors.*;
import com.google.protobuf.MessageOrBuilder;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.docs.*;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.soulfiremc.builddata.BuildData;
import com.soulfiremc.grpc.generated.ApiDocsProto;
import com.soulfiremc.grpc.generated.ApiFieldDocs;
import com.soulfiremc.grpc.generated.ApiMethodDocs;
import com.soulfiremc.grpc.generated.LoginServiceGrpc;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.ProtoFileDescriptorSupplier;

import java.net.URI;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

final class OpenApiSpecGenerator {
  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
  private static final String HEALTH_SERVICE_NAME = "grpc.health.v1.Health";
  private static final String HTTP_SERVICE_SUFFIX = "_HTTP";
  private static final String OPEN_API_VERSION = "3.1.0";
  private static final String JSON_SCHEMA_DIALECT = "https://spec.openapis.org/oas/3.1/dialect/base";
  private static final String ERROR_SCHEMA_NAME = "SoulFireGrpcError";
  private static final List<DocServicePlugin> DOC_SERVICE_PLUGINS = immutableServiceLoader(DocServicePlugin.class);
  private static final List<DescriptiveTypeInfoProvider> DESCRIPTIVE_TYPE_INFO_PROVIDERS = immutableServiceLoader(DescriptiveTypeInfoProvider.class);

  private final ServiceSpecification specification;
  private final String publicAddress;
  private final Map<String, ProtoMethodMetadata> protoMethodMetadata;
  private final Map<String, ProtoFieldMetadata> protoFieldMetadata;
  private final Map<String, Descriptor> protoMessageDescriptors;
  private final Map<String, EnumDescriptor> protoEnumDescriptors;
  private final Map<String, ServiceInfo> rawServiceByName;
  private final Set<String> transcodedServiceNames;
  private final Map<String, StructInfo> structByTypeName;
  private final Map<String, EnumInfo> enumByTypeName;
  private final Map<String, String> canonicalSchemaNameByTypeName;
  private final Map<String, String> docStrings;
  private final Set<String> operationIds = new HashSet<>();
  private final Set<String> usedSchemaNames = new TreeSet<>();

  private OpenApiSpecGenerator(
    ServiceSpecification specification,
    String publicAddress,
    ProtoDescriptorMetadata protoDescriptorMetadata
  ) {
    this.specification = specification;
    this.publicAddress = normalizeBaseUrl(publicAddress);
    this.protoMethodMetadata = protoDescriptorMetadata.methodMetadata();
    this.protoFieldMetadata = protoDescriptorMetadata.fieldMetadata();
    this.protoMessageDescriptors = protoDescriptorMetadata.messageDescriptors();
    this.protoEnumDescriptors = protoDescriptorMetadata.enumDescriptors();
    this.rawServiceByName = specification.services().stream()
      .filter(service -> !service.name().endsWith(HTTP_SERVICE_SUFFIX))
      .collect(Collectors.toMap(ServiceInfo::name, Function.identity()));
    this.transcodedServiceNames = specification.services().stream()
      .filter(OpenApiSpecGenerator::isTranscodedService)
      .map(ServiceInfo::name)
      .map(OpenApiSpecGenerator::baseServiceName)
      .collect(Collectors.toUnmodifiableSet());
    this.structByTypeName = createStructLookup(specification.structs());
    this.enumByTypeName = specification.enums().stream()
      .collect(Collectors.toMap(EnumInfo::name, Function.identity()));
    this.canonicalSchemaNameByTypeName = createCanonicalSchemaLookup(specification.structs(), specification.enums());
    this.docStrings = specification.docStrings().entrySet().stream()
      .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().docString(), (a, _) -> a));
  }

  static ObjectNode generate(List<ServiceConfig> services, String publicAddress) {
    var serviceSpecification = buildServiceSpecification(services);
    var protoDescriptorMetadata = loadProtoDescriptorMetadata(services);
    return new OpenApiSpecGenerator(serviceSpecification, publicAddress, protoDescriptorMetadata).generate();
  }

  private ObjectNode generate() {
    var document = JSON_MAPPER.createObjectNode();
    document.put("openapi", OPEN_API_VERSION);
    document.put("jsonSchemaDialect", JSON_SCHEMA_DIALECT);
    document.set("info", buildInfo());
    document.set("servers", buildServers());
    document.set("tags", buildTags());
    document.set("paths", buildPaths());
    document.set("components", buildComponents());
    document.set("security", buildDefaultSecurity());
    document.set("externalDocs", buildExternalDocs());
    document.set("x-soulfire", buildSoulFireExtension());
    return document;
  }

  private ObjectNode buildInfo() {
    var info = JSON_MAPPER.createObjectNode();
    info.put("title", "Soulfire HTTP API");
    info.put("version", BuildData.VERSION);
    info.put(
      "description",
      """
        Generated from Soulfire's Armeria gRPC metadata.

        This document only covers HTTP-accessible unary operations:
        - unframed JSON POST routes generated from gRPC services
        - additional HTTP/JSON transcoding routes exposed by Armeria

        It does not describe streaming RPCs, gRPC-Web framing, reflection, or other non-HTTP transports.

        Authorization:
        - Bearer: `Authorization: Bearer <api-jwt>`
        - Basic: `Authorization: Basic <base64(username:api-jwt)>`

        `LoginService` is public. Every other operation requires an API token audience accepted by Soulfire.
        """
    );
    return info;
  }

  private ArrayNode buildServers() {
    var servers = JSON_MAPPER.createArrayNode();
    servers.addObject()
      .put("url", publicAddress)
      .put("description", "Configured Soulfire public API address");
    var templateDefaults = serverTemplateDefaults(publicAddress);
    var templateServer = servers.addObject();
    templateServer
      .put("url", "{scheme}://{host}:{port}/{basePath}")
      .put("description", "User-selected server");
    var variables = templateServer.putObject("variables");
    addServerVariable(variables, "scheme", templateDefaults.scheme(), List.of("http", "https"));
    addServerVariable(variables, "host", templateDefaults.host(), List.of());
    addServerVariable(variables, "port", templateDefaults.port(), List.of());
    addServerVariable(variables, "basePath", templateDefaults.basePath(), List.of());
    return servers;
  }

  private static void addServerVariable(
    ObjectNode variables,
    String name,
    String defaultValue,
    List<String> allowedValues
  ) {
    var variable = variables.putObject(name);
    variable.put("default", defaultValue);
    if (!allowedValues.isEmpty()) {
      var enumValues = variable.putArray("enum");
      allowedValues.forEach(enumValues::add);
    }
  }

  private ArrayNode buildTags() {
    var tags = JSON_MAPPER.createArrayNode();

    specification.services().stream()
      .map(ServiceInfo::name)
      .map(OpenApiSpecGenerator::baseServiceName)
      .filter(OpenApiSpecGenerator::isExportedService)
      .filter(this::hasDocumentedUnaryMethods)
      .distinct()
      .sorted()
      .forEach(serviceName -> {
        var tag = tags.addObject();
        tag.put("name", simpleName(serviceName));
        var description = docString(serviceName);
        if (!description.isBlank()) {
          tag.put("description", description);
        }
      });

    return tags;
  }

  private ObjectNode buildPaths() {
    var paths = JSON_MAPPER.createObjectNode();

    specification.services().stream()
      .filter(service -> isExportedService(baseServiceName(service)))
      .sorted(Comparator.comparing(ServiceInfo::name))
      .forEach(service -> {
        var transcoded = isTranscodedService(service);
        var baseServiceName = baseServiceName(service);
        var rawService = rawServiceByName.get(baseServiceName);

        service.methods().stream()
          .sorted(Comparator.comparing(MethodInfo::name).thenComparing(method -> method.httpMethod().name()))
          .forEach(method -> addOperations(paths, service, rawService, method, transcoded));
      });

    return paths;
  }

  private void addOperations(
    ObjectNode paths,
    ServiceInfo service,
    ServiceInfo rawService,
    MethodInfo method,
    boolean transcoded
  ) {
    var baseServiceName = baseServiceName(service);
    var methodKey = methodKey(baseServiceName, method.name());
    var metadata = protoMethodMetadata.get(methodKey);
    if (metadata == null || !metadata.unary()) {
      return;
    }
    if (!transcoded && metadata.hasHttpBindings() && transcodedServiceNames.contains(baseServiceName)) {
      return;
    }

    if (transcoded) {
      var httpMethod = method.httpMethod().name().toLowerCase(Locale.ROOT);
      for (var index = 0; index < metadata.httpBindings().size(); index++) {
        var binding = metadata.httpBindings().get(index);
        if (!binding.method().equals(httpMethod)) {
          continue;
        }

        addOperation(
          paths,
          binding.path(),
          binding.method(),
          service,
          rawService,
          method,
          metadata,
          binding,
          index
        );
      }
      return;
    }

    var endpoints = method.endpoints().stream()
      .sorted(Comparator.comparing(EndpointInfo::pathMapping))
      .toList();
    for (var index = 0; index < endpoints.size(); index++) {
      var httpMethod = method.httpMethod().name().toLowerCase(Locale.ROOT);
      addOperation(
        paths,
        normalizeArmeriaPath(endpoints.get(index).pathMapping()),
        httpMethod,
        service,
        rawService,
        method,
        metadata,
        null,
        index
      );
    }
  }

  private void addOperation(
    ObjectNode paths,
    String path,
    String httpMethod,
    ServiceInfo service,
    ServiceInfo rawService,
    MethodInfo method,
    ProtoMethodMetadata metadata,
    ProtoHttpBinding httpBinding,
    int routeIndex
  ) {
    var pathItem = literalPathItem(paths, normalizePath(path));
    if (pathItem.has(httpMethod)) {
      return;
    }

    pathItem.set(httpMethod, buildOperation(service, rawService, method, metadata, httpBinding, routeIndex));
  }

  private ObjectNode buildOperation(
    ServiceInfo service,
    ServiceInfo rawService,
    MethodInfo method,
    ProtoMethodMetadata metadata,
    ProtoHttpBinding httpBinding,
    int routeIndex
  ) {
    var baseServiceName = baseServiceName(service);
    var inputStruct = findInputStruct(rawService, method, metadata);
    var transcoded = httpBinding != null;
    var operation = JSON_MAPPER.createObjectNode();

    var description = operationDescription(baseServiceName, method.name(), metadata);
    var summary = firstNonBlank(metadata.displayName(), firstSentence(description));
    if (!summary.isBlank()) {
      operation.put("summary", summary);
    }
    if (!description.isBlank()) {
      operation.put("description", description);
    }

    var operationId = operationId(metadata, routeIndex);
    if (!operationIds.add(operationId)) {
      throw new IllegalStateException("Duplicate OpenAPI operationId '%s'".formatted(operationId));
    }
    operation.put("operationId", operationId);
    operation.put("x-soulfire-transport", transcoded ? "http-json-transcoding" : "unframed-grpc-json");
    operation.put("x-soulfire-grpc-service", baseServiceName);
    operation.put("x-soulfire-grpc-method", method.name());
    operation.put("x-soulfire-unary-only", true);
    operation.putArray("tags").add(simpleName(baseServiceName));
    applyOperationMetadata(operation, metadata);

    if (LoginServiceGrpc.SERVICE_NAME.equals(baseServiceName)) {
      operation.set("security", JSON_MAPPER.createArrayNode());
    }

    var parameters = buildOperationParameters(baseServiceName, method, inputStruct);
    if (!parameters.isEmpty()) {
      operation.set("parameters", parameters);
    }

    var requestBody = buildRequestBody(
      baseServiceName,
      rawService,
      method,
      inputStruct,
      metadata,
      httpBinding
    );
    if (requestBody != null) {
      operation.set("requestBody", requestBody);
    }

    operation.set("responses", buildResponses(baseServiceName, method, metadata));
    return operation;
  }

  private ArrayNode buildOperationParameters(String serviceName, MethodInfo method, StructInfo inputStruct) {
    var parameters = JSON_MAPPER.createArrayNode();

    method.parameters().stream()
      .filter(parameter -> parameter.location() == FieldLocation.PATH || parameter.location() == FieldLocation.QUERY)
      .forEach(parameter -> {
        var parameterNode = parameters.addObject();
        parameterNode.put("name", parameter.name());
        parameterNode.put("in", parameter.location() == FieldLocation.PATH ? "path" : "query");
        var fieldMetadata = findFieldMetadata(inputStruct, parameter.name());
        parameterNode.put("required", parameter.location() == FieldLocation.PATH || isRequired(parameter.requirement(), fieldMetadata));

        var description = parameterDescription(serviceName, method.name(), parameter.name(), inputStruct);
        if (!description.isBlank()) {
          parameterNode.put("description", description);
        }

        var schema = schemaForParameter(parameter, fieldMetadata);
        applyFieldSchemaMetadata(schema, fieldMetadata);
        parameterNode.set("schema", schema);
      });

    return parameters;
  }

  private ObjectNode buildRequestBody(
    String serviceName,
    ServiceInfo rawService,
    MethodInfo method,
    StructInfo inputStruct,
    ProtoMethodMetadata metadata,
    ProtoHttpBinding httpBinding
  ) {
    ObjectNode schema = null;
    var requestExample = firstRawRequestExample(rawService, method.name());

    if (httpBinding == null) {
      if (method.useParameterAsRoot() && method.parameters().size() == 1) {
        schema = schemaForMessage(metadata.inputDescriptor());
      } else if (!method.parameters().isEmpty()) {
        schema = objectSchemaForParameters(serviceName, method.name(), method.parameters(), inputStruct);
      }
    } else if (!httpBinding.body().isBlank()) {
      schema = schemaForHttpBody(method, inputStruct, metadata, httpBinding.body());
      if (schema != null) {
        if (method.parameters().stream().anyMatch(parameter ->
          parameter.location() == FieldLocation.PATH || parameter.location() == FieldLocation.QUERY)) {
          requestExample = null;
        }
      }
    }

    if (schema == null) {
      return null;
    }

    var content = JSON_MAPPER.createObjectNode();
    var jsonContent = content.putObject("application/json");
    jsonContent.set("schema", schema);
    if (requestExample != null && !requestExample.isBlank()) {
      jsonContent.set("example", tryReadJson(requestExample));
    }

    var requestBody = JSON_MAPPER.createObjectNode();
    requestBody.put("required", true);
    requestBody.set("content", content);
    return requestBody;
  }

  private ObjectNode schemaForHttpBody(
    MethodInfo method,
    StructInfo inputStruct,
    ProtoMethodMetadata metadata,
    String body
  ) {
    if (!"*".equals(body)) {
      var bodyField = findProtoField(metadata.inputDescriptor(), body);
      if (bodyField == null) {
        throw new IllegalStateException(
          "HTTP body field '%s' does not exist in %s".formatted(body, metadata.inputDescriptor().getFullName())
        );
      }

      var schema = schemaForProtoField(bodyField);
      schema.put("title", method.name() + "RequestBody");
      return schema;
    }

    var fieldsOutsideBody = method.parameters().stream()
      .filter(parameter -> parameter.location() == FieldLocation.PATH || parameter.location() == FieldLocation.QUERY)
      .map(ParamInfo::name)
      .map(fieldPath -> findTopLevelProtoField(metadata.inputDescriptor(), fieldPath))
      .filter(Objects::nonNull)
      .collect(Collectors.toUnmodifiableSet());
    return buildProtoMessageSchema(
      metadata.inputDescriptor(),
      inputStruct,
      method.name() + "RequestBody",
      fieldsOutsideBody
    );
  }

  private ObjectNode objectSchemaForParameters(
    String serviceName,
    String methodName,
    List<ParamInfo> parameters,
    StructInfo inputStruct
  ) {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("title", methodName + "RequestBody");
    schema.put("type", "object");
    schema.put("additionalProperties", false);

    var properties = schema.putObject("properties");
    var required = schema.putArray("required");

    parameters.forEach(parameter -> {
      var fieldMetadata = findFieldMetadata(inputStruct, parameter.name());
      var propertyName = fieldMetadata == null ? parameter.name() : fieldMetadata.jsonName();
      properties.set(propertyName, schemaWithDescription(
        applyFieldSchemaMetadata(schemaForParameter(parameter, fieldMetadata), fieldMetadata),
        parameterDescription(serviceName, methodName, parameter.name(), inputStruct)
      ));
      if (isRequired(parameter.requirement(), fieldMetadata)) {
        required.add(propertyName);
      }
    });

    return schema;
  }

  private ObjectNode buildResponses(String serviceName, MethodInfo method, ProtoMethodMetadata metadata) {
    var responses = JSON_MAPPER.createObjectNode();
    var successResponse = responses.putObject("200");
    var returnDescription = docString("%s/%s:return".formatted(serviceName, method.name()));
    successResponse.put("description", returnDescription.isBlank() ? "Successful response." : returnDescription);
    successResponse.putObject("content")
      .putObject("application/json")
      .set("schema", schemaForMessage(metadata.outputDescriptor()));

    if (!LoginServiceGrpc.SERVICE_NAME.equals(serviceName)) {
      responses.set("401", errorResponse("Missing or invalid API authorization header."));
      responses.set("403", errorResponse("The authenticated user does not have the required permission."));
    }

    responses.set("500", errorResponse("Unexpected server-side failure while handling the RPC."));
    if (LoginServiceGrpc.SERVICE_NAME.equals(serviceName)) {
      responses.set("429", errorResponse("Login flow or credential validation was rate limited."));
    }

    if (!metadata.declaredExceptions().isEmpty()) {
      responses.putObject("default")
        .put("description", "gRPC failure mapped to HTTP by Armeria's JSON unframed error handler.")
        .putObject("content")
        .putObject("application/json")
        .set("schema", schemaRef(ERROR_SCHEMA_NAME));
    }

    return responses;
  }

  private ObjectNode errorResponse(String description) {
    var response = JSON_MAPPER.createObjectNode();
    response.put("description", description);
    response.putObject("content")
      .putObject("application/json")
      .set("schema", schemaRef(ERROR_SCHEMA_NAME));
    return response;
  }

  private ObjectNode buildComponents() {
    var components = JSON_MAPPER.createObjectNode();
    components.set("securitySchemes", buildSecuritySchemes());

    var schemas = components.putObject("schemas");
    schemas.set(ERROR_SCHEMA_NAME, buildErrorSchema());

    var pending = new ArrayDeque<>(usedSchemaNames);
    var generated = new HashSet<String>();
    while (!pending.isEmpty()) {
      var schemaName = pending.removeFirst();
      if (!generated.add(schemaName)) {
        continue;
      }

      var struct = structByTypeName.get(schemaName);
      if (struct != null) {
        var structSchema = buildStructSchema(struct, pending, generated);
        schemas.set(schemaName, structSchema);
        enqueueReferencedSchemaNames(structSchema, pending, generated);
        continue;
      }

      var enumInfo = enumByTypeName.get(schemaName);
      if (enumInfo != null) {
        var enumSchema = buildEnumSchema(enumInfo);
        schemas.set(schemaName, enumSchema);
        enqueueReferencedSchemaNames(enumSchema, pending, generated);
      }
    }

    return components;
  }

  private ObjectNode buildStructSchema(StructInfo struct, Deque<String> pending, Set<String> generated) {
    var descriptor = protoMessageDescriptors.get(struct.name());
    if (descriptor != null) {
      var wellKnownSchema = wellKnownTypeSchema(descriptor.getFullName());
      if (wellKnownSchema != null) {
        return wellKnownSchema;
      }
      return buildProtoMessageSchema(descriptor, struct);
    }

    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);

    var description = firstNonBlank(normalizeDocBlock(struct.descriptionInfo().docString()), docString(struct.name()));
    if (!description.isBlank()) {
      schema.put("description", description);
    }

    var properties = schema.putObject("properties");
    var required = schema.putArray("required");
    for (var field : struct.fields()) {
      var fieldMetadata = findFieldMetadata(struct, field.name());
      var fieldDescription = firstNonBlank(
        normalizeDocBlock(field.descriptionInfo().docString()),
        docString("%s/%s".formatted(struct.name(), field.name()))
      );
      var fieldSchema = schemaWithDescription(
        applyFieldSchemaMetadata(schemaForType(field.typeSignature()), fieldMetadata),
        fieldDescription
      );
      properties.set(field.name(), fieldSchema);

      collectReferencedSchemaNames(field.typeSignature(), pending, generated);
      if (isRequired(field.requirement(), fieldMetadata)) {
        required.add(field.name());
      }
    }

    return schema;
  }

  private ObjectNode buildProtoMessageSchema(Descriptor descriptor, StructInfo struct) {
    return buildProtoMessageSchema(descriptor, struct, "", Set.of());
  }

  private ObjectNode buildProtoMessageSchema(
    Descriptor descriptor,
    StructInfo struct,
    String title,
    Set<FieldDescriptor> excludedFields
  ) {
    var schema = JSON_MAPPER.createObjectNode();
    if (!title.isBlank()) {
      schema.put("title", title);
    }
    schema.put("type", "object");
    schema.put("additionalProperties", false);

    var description = firstNonBlank(
      struct == null ? "" : normalizeDocBlock(struct.descriptionInfo().docString()),
      docString(descriptor.getFullName())
    );
    if (!description.isBlank()) {
      schema.put("description", description);
    }

    var properties = schema.putObject("properties");
    var required = schema.putArray("required");
    for (var field : descriptor.getFields()) {
      if (excludedFields.contains(field)) {
        continue;
      }

      var fieldInfo = struct == null ? null : findFieldInfo(struct, field.getName());
      var fieldMetadata = protoFieldMetadata.get(fieldKey(descriptor.getFullName(), field.getName()));
      var fieldDescription = firstNonBlank(
        fieldInfo == null ? "" : normalizeDocBlock(fieldInfo.descriptionInfo().docString()),
        docString("%s/%s".formatted(descriptor.getFullName(), field.getName()))
      );
      var fieldSchema = schemaWithDescription(
        applyFieldSchemaMetadata(schemaForProtoField(field), fieldMetadata),
        fieldDescription
      );
      properties.set(field.getJsonName(), fieldSchema);

      var requirement = fieldInfo == null ? FieldRequirement.UNSPECIFIED : fieldInfo.requirement();
      if (isRequired(requirement, fieldMetadata)) {
        required.add(field.getJsonName());
      }
    }

    return schema;
  }

  private ObjectNode buildEnumSchema(EnumInfo enumInfo) {
    var descriptor = protoEnumDescriptors.get(enumInfo.name());
    if (descriptor != null && "google.protobuf.NullValue".equals(descriptor.getFullName())) {
      return schemaForEnum(descriptor);
    }

    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "string");
    var description = firstNonBlank(normalizeDocBlock(enumInfo.descriptionInfo().docString()), docString(enumInfo.name()));
    if (!description.isBlank()) {
      schema.put("description", description);
    }
    var values = schema.putArray("enum");
    enumInfo.values().forEach(value -> values.add(value.name()));
    return schema;
  }

  private ObjectNode buildErrorSchema() {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    var properties = schema.putObject("properties");
    properties.putObject("code")
      .put("type", "integer")
      .put("format", "int32")
      .put("description", "Numeric gRPC status code.");
    properties.putObject("grpc-code")
      .put("type", "string")
      .put("description", "Named gRPC status code.");
    properties.putObject("message")
      .put("type", "string")
      .put("description", "Human-readable error message.");
    properties.putObject("stack-trace")
      .put("type", "string")
      .put("description", "Stack trace included only when verbose responses are enabled.");
    properties.putObject("details")
      .put("type", "array")
      .put("description", "Optional Google RPC error detail payloads.")
      .putObject("items")
      .put("type", "object")
      .put("additionalProperties", true);

    var required = schema.putArray("required");
    required.add("code");
    required.add("grpc-code");
    return schema;
  }

  private ObjectNode buildSecuritySchemes() {
    var securitySchemes = JSON_MAPPER.createObjectNode();

    securitySchemes.putObject("bearerAuth")
      .put("type", "http")
      .put("scheme", "bearer")
      .put("bearerFormat", "JWT")
      .put("description", "Preferred API authentication. Use a JWT with the `api` audience.");

    securitySchemes.putObject("basicTokenAuth")
      .put("type", "http")
      .put("scheme", "basic")
      .put(
        "description",
        "Compatibility authentication. Encode `username:api-jwt` as the HTTP Basic credentials. Soulfire ignores the username and validates the token in the password slot."
      );

    return securitySchemes;
  }

  private ArrayNode buildDefaultSecurity() {
    var security = JSON_MAPPER.createArrayNode();
    security.addObject().putArray("bearerAuth");
    security.addObject().putArray("basicTokenAuth");
    return security;
  }

  private ObjectNode buildExternalDocs() {
    return JSON_MAPPER.createObjectNode()
      .put("description", "Interactive Armeria DocService")
      .put("url", appendPath(publicAddress, "/docs"));
  }

  private ObjectNode buildSoulFireExtension() {
    var extension = JSON_MAPPER.createObjectNode();
    extension.put("httpJsonTranscodingEnabled", true);
    extension.put("unframedJsonEnabled", true);
    extension.put("openApiDocument", appendPath(publicAddress, "/openapi.json"));
    extension.put("docService", appendPath(publicAddress, "/docs"));
    extension.put("mcp", appendPath(publicAddress, "/mcp"));

    var limitations = extension.putArray("limitations");
    limitations.add("Only unary RPC methods are included.");
    limitations.add("Streaming RPCs stay gRPC-only and are intentionally excluded.");
    limitations.add("Operations describe the JSON HTTP surface, not framed gRPC semantics.");

    var authorization = extension.putObject("authorization");
    authorization.put("publicService", LoginServiceGrpc.SERVICE_NAME);
    authorization.put("bearerHeader", "Authorization: Bearer <api-jwt>");
    authorization.put("basicHeader", "Authorization: Basic <base64(username:api-jwt)>");
    return extension;
  }

  private String operationDescription(String serviceName, String methodName, ProtoMethodMetadata metadata) {
    return firstNonBlank(metadata.description(), sanitizeMethodDescription(
      docString("%s/%s".formatted(serviceName, methodName))
    ));
  }

  private void applyOperationMetadata(ObjectNode operation, ProtoMethodMetadata metadata) {
    if (!metadata.permissions().isEmpty()) {
      var permissions = operation.putArray("x-soulfire-permissions");
      metadata.permissions().forEach(permissions::add);
    }
    if (!metadata.scope().isBlank()) {
      operation.put("x-soulfire-scope", metadata.scope());
    }
    if (!metadata.preconditions().isEmpty()) {
      var preconditions = operation.putArray("x-soulfire-preconditions");
      metadata.preconditions().forEach(preconditions::add);
    }
    if (!metadata.execution().isBlank()) {
      operation.put("x-soulfire-execution", metadata.execution());
    }
    if (!metadata.sideEffects().isEmpty()) {
      var sideEffects = operation.putArray("x-soulfire-side-effects");
      metadata.sideEffects().forEach(sideEffects::add);
    }
  }

  private static String sanitizeMethodDescription(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }

    var sanitized = new ArrayList<String>();
    for (var line : normalizeDocBlock(value).lines().toList()) {
      if (line.isBlank()) {
        if (!sanitized.isEmpty() && !sanitized.getLast().isBlank()) {
          sanitized.add("");
        }
        continue;
      }

      if (line.startsWith("Authentication:")
        || line.startsWith("Transport:")
        || line.startsWith("Required permission:")
        || line.startsWith("Permissions required:")
        || line.startsWith("Returns:")
        || line.startsWith("Errors:")
        || line.startsWith("Error cases:")
        || line.startsWith("Parameters:")
        || line.startsWith("Timeout:")
        || line.startsWith("- Permission")
        || line.startsWith("- Scope:")
        || line.startsWith("- Requires:")
        || line.startsWith("- Execution:")
        || line.startsWith("- Side effects:")
        || line.startsWith("Requires")) {
        break;
      }

      sanitized.add(line);
    }

    while (!sanitized.isEmpty() && sanitized.getLast().isBlank()) {
      sanitized.removeLast();
    }

    return String.join("\n", sanitized);
  }

  private String parameterDescription(
    String serviceName,
    String methodName,
    String parameterName,
    StructInfo inputStruct
  ) {
    var direct = docString("%s/%s:param/%s".formatted(serviceName, methodName, parameterName));
    if (!direct.isBlank()) {
      return direct;
    }

    if (inputStruct == null) {
      return "";
    }

    return fieldDescription(inputStruct, parameterName);
  }

  private String fieldDescription(StructInfo struct, String fieldPath) {
    if (struct == null || fieldPath.isBlank()) {
      return "";
    }

    var parts = fieldPath.split("\\.");
    StructInfo currentStruct = struct;
    FieldInfo currentField = null;
    for (var index = 0; index < parts.length; index++) {
      currentField = findFieldInfo(currentStruct, parts[index]);
      if (currentField == null) {
        return "";
      }

      if (index < parts.length - 1) {
        currentStruct = structByTypeName.get(currentField.typeSignature().signature());
        if (currentStruct == null) {
          return "";
        }
      }
    }

    if (currentField == null) {
      return "";
    }

    return firstNonBlank(
      normalizeDocBlock(currentField.descriptionInfo().docString()),
      docString("%s/%s".formatted(currentStruct.name(), currentField.name()))
    );
  }

  private ProtoFieldMetadata findFieldMetadata(StructInfo struct, String fieldPath) {
    if (struct == null || fieldPath.isBlank()) {
      return null;
    }

    var parts = fieldPath.split("\\.");
    StructInfo currentStruct = struct;
    FieldInfo currentField = null;
    for (var index = 0; index < parts.length; index++) {
      currentField = findFieldInfo(currentStruct, parts[index]);
      if (currentField == null) {
        return null;
      }

      if (index < parts.length - 1) {
        currentStruct = structByTypeName.get(currentField.typeSignature().signature());
        if (currentStruct == null) {
          return null;
        }
      }
    }

    return currentField == null ? null : findProtoFieldMetadata(currentStruct, currentField);
  }

  private static FieldDescriptor findTopLevelProtoField(Descriptor descriptor, String fieldPath) {
    if (fieldPath.isBlank()) {
      return null;
    }

    return findDirectProtoField(descriptor, fieldPath.split("\\.", 2)[0]);
  }

  private static FieldDescriptor findProtoField(Descriptor descriptor, String fieldPath) {
    if (fieldPath.isBlank()) {
      return null;
    }

    var parts = fieldPath.split("\\.");
    var currentDescriptor = descriptor;
    FieldDescriptor currentField = null;
    for (var index = 0; index < parts.length; index++) {
      currentField = findDirectProtoField(currentDescriptor, parts[index]);
      if (currentField == null) {
        return null;
      }

      if (index < parts.length - 1) {
        if (currentField.getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
          return null;
        }
        currentDescriptor = currentField.getMessageType();
      }
    }

    return currentField;
  }

  private static FieldDescriptor findDirectProtoField(Descriptor descriptor, String fieldName) {
    return descriptor.getFields().stream()
      .filter(field -> field.getName().equals(fieldName) || field.getJsonName().equals(fieldName))
      .findFirst()
      .orElse(null);
  }

  private FieldInfo findFieldInfo(StructInfo struct, String fieldName) {
    return struct.fields().stream()
      .filter(field -> {
        if (field.name().equals(fieldName)) {
          return true;
        }

        var metadata = findProtoFieldMetadata(struct, field);
        return metadata != null && metadata.jsonName().equals(fieldName);
      })
      .findFirst()
      .orElse(null);
  }

  private ProtoFieldMetadata findProtoFieldMetadata(StructInfo struct, FieldInfo field) {
    var metadata = protoFieldMetadata.get(fieldKey(struct.name(), field.name()));
    if (metadata != null || struct.alias() == null) {
      return metadata;
    }

    return protoFieldMetadata.get(fieldKey(struct.alias(), field.name()));
  }

  static boolean isRequired(FieldRequirement requirement, Set<FieldBehavior> behaviors) {
    if (behaviors.contains(FieldBehavior.REQUIRED)) {
      return true;
    }
    if (behaviors.contains(FieldBehavior.OPTIONAL)) {
      return false;
    }

    return requirement == FieldRequirement.REQUIRED;
  }

  private static boolean isRequired(FieldRequirement requirement, ProtoFieldMetadata fieldMetadata) {
    return isRequired(
      requirement,
      fieldMetadata == null ? Set.of() : fieldMetadata.behaviors()
    );
  }

  private ObjectNode applyFieldSchemaMetadata(ObjectNode schema, ProtoFieldMetadata metadata) {
    return applyFieldSchemaMetadata(
      schema,
      metadata == null ? Set.of() : metadata.behaviors(),
      metadata == null ? "" : metadata.format(),
      metadata == null ? "" : metadata.example()
    );
  }

  static ObjectNode applyFieldSchemaMetadata(
    ObjectNode schema,
    Set<FieldBehavior> behaviors,
    String format,
    String example
  ) {
    if (!format.isBlank()) {
      schema.put("format", format);
    }
    if (!example.isBlank()) {
      schema.set("example", tryReadJson(example));
    }
    if (behaviors.contains(FieldBehavior.OUTPUT_ONLY)) {
      schema.put("readOnly", true);
    }
    if (behaviors.contains(FieldBehavior.INPUT_ONLY)) {
      schema.put("writeOnly", true);
    }

    var unsupportedBehaviors = behaviors.stream()
      .filter(behavior -> switch (behavior) {
        case OPTIONAL, REQUIRED, OUTPUT_ONLY, INPUT_ONLY -> false;
        default -> true;
      })
      .map(FieldBehavior::name)
      .sorted()
      .toList();
    if (!unsupportedBehaviors.isEmpty()) {
      var fieldBehaviors = schema.putArray("x-google-field-behaviors");
      unsupportedBehaviors.forEach(fieldBehaviors::add);
    }

    return schema;
  }

  private StructInfo findInputStruct(ServiceInfo rawService, MethodInfo method, ProtoMethodMetadata metadata) {
    if (rawService != null) {
      var rawMethod = rawService.methods().stream()
        .filter(candidate -> candidate.name().equals(method.name()))
        .findFirst()
        .orElse(null);
      if (rawMethod != null && rawMethod.useParameterAsRoot() && rawMethod.parameters().size() == 1) {
        return structByTypeName.get(rawMethod.parameters().getFirst().typeSignature().signature());
      }
    }

    return structByTypeName.get(metadata.inputDescriptor().getFullName());
  }

  private String firstRawRequestExample(ServiceInfo rawService, String methodName) {
    if (rawService == null) {
      return null;
    }

    return rawService.methods().stream()
      .filter(method -> method.name().equals(methodName))
      .flatMap(method -> method.exampleRequests().stream())
      .filter(example -> !example.isBlank())
      .findFirst()
      .orElse(null);
  }

  private ObjectNode schemaForType(TypeSignature typeSignature) {
    if (typeSignature instanceof MapTypeSignature mapTypeSignature) {
      var schema = JSON_MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.set("additionalProperties", schemaForType(mapTypeSignature.valueTypeSignature()));
      collectReferencedSchemaNames(mapTypeSignature.valueTypeSignature(), null, null);
      return schema;
    }

    if (typeSignature instanceof ContainerTypeSignature containerTypeSignature) {
      if (typeSignature.type() == TypeSignatureType.OPTIONAL && !containerTypeSignature.typeParameters().isEmpty()) {
        return nullableSchema(schemaForType(containerTypeSignature.typeParameters().getFirst()));
      }

      var containerName = typeSignature.name().toLowerCase(Locale.ROOT);
      if (typeSignature.type() == TypeSignatureType.ITERABLE
        || List.of("repeated", "list", "array", "set").contains(containerName)) {
        var schema = JSON_MAPPER.createObjectNode();
        schema.put("type", "array");
        var itemType = containerTypeSignature.typeParameters().getFirst();
        schema.set("items", schemaForType(itemType));
        collectReferencedSchemaNames(itemType, null, null);
        return schema;
      }
    }

    if (typeSignature.type() == TypeSignatureType.ENUM || typeSignature.type() == TypeSignatureType.STRUCT) {
      var schemaName = canonicalSchemaNameByTypeName.getOrDefault(typeSignature.signature(), typeSignature.name());
      if (typeSignature.type() == TypeSignatureType.STRUCT) {
        var descriptor = protoMessageDescriptors.get(schemaName);
        if (descriptor != null) {
          return schemaForMessage(descriptor);
        }
      } else {
        var descriptor = protoEnumDescriptors.get(schemaName);
        if (descriptor != null) {
          return schemaForEnum(descriptor);
        }
      }

      markSchemaUsed(schemaName);
      return schemaRef(schemaName);
    }

    if (typeSignature.type() == TypeSignatureType.BASE) {
      return baseSchema(typeSignature.name());
    }

    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", true);
    return schema;
  }

  private ObjectNode schemaForParameter(ParamInfo parameter, ProtoFieldMetadata fieldMetadata) {
    return fieldMetadata == null
      ? schemaForType(parameter.typeSignature())
      : schemaForProtoField(fieldMetadata.descriptor());
  }

  private ObjectNode schemaForProtoField(FieldDescriptor field) {
    ObjectNode schema;
    if (field.isMapField()) {
      var valueField = field.getMessageType().findFieldByName("value");
      schema = JSON_MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.set("additionalProperties", schemaForSingularProtoField(valueField));
    } else if (field.isRepeated()) {
      schema = JSON_MAPPER.createObjectNode();
      schema.put("type", "array");
      schema.set("items", schemaForSingularProtoField(field));
    } else {
      schema = schemaForSingularProtoField(field);
    }

    return field.toProto().getProto3Optional() ? nullableSchema(schema) : schema;
  }

  private ObjectNode schemaForSingularProtoField(FieldDescriptor field) {
    return switch (field.getType()) {
      case BOOL -> scalarSchema("boolean", null, "bool");
      case BYTES -> scalarSchema("string", "byte", "bytes");
      case STRING -> scalarSchema("string", null, "string");
      case DOUBLE -> floatingPointSchema("double", "double");
      case FLOAT -> floatingPointSchema("float", "float");
      case INT32, SINT32, SFIXED32 -> scalarSchema("integer", "int32", field.getType().name().toLowerCase(Locale.ROOT));
      case UINT32, FIXED32 -> unsignedInt32Schema(field.getType().name().toLowerCase(Locale.ROOT));
      case INT64, SINT64, SFIXED64 -> signedInt64Schema(field.getType().name().toLowerCase(Locale.ROOT));
      case UINT64, FIXED64 -> unsignedInt64Schema(field.getType().name().toLowerCase(Locale.ROOT));
      case ENUM -> schemaForEnum(field.getEnumType());
      case GROUP, MESSAGE -> schemaForMessage(field.getMessageType());
    };
  }

  private ObjectNode schemaForMessage(Descriptor descriptor) {
    var wellKnownSchema = wellKnownTypeSchema(descriptor.getFullName());
    if (wellKnownSchema != null) {
      return wellKnownSchema;
    }

    var schemaName = canonicalSchemaNameByTypeName.getOrDefault(descriptor.getFullName(), descriptor.getFullName());
    markSchemaUsed(schemaName);
    return schemaRef(schemaName);
  }

  private ObjectNode schemaForEnum(EnumDescriptor descriptor) {
    if ("google.protobuf.NullValue".equals(descriptor.getFullName())) {
      return JSON_MAPPER.createObjectNode()
        .put("type", "null")
        .put("x-protobuf-type", descriptor.getFullName());
    }

    var schemaName = canonicalSchemaNameByTypeName.getOrDefault(descriptor.getFullName(), descriptor.getFullName());
    markSchemaUsed(schemaName);
    return schemaRef(schemaName);
  }

  private static ObjectNode wellKnownTypeSchema(String typeName) {
    return switch (typeName) {
      case "google.protobuf.Any" -> anySchema();
      case "google.protobuf.BoolValue" -> nullableSchema(scalarSchema("boolean", null, typeName));
      case "google.protobuf.BytesValue" -> nullableSchema(scalarSchema("string", "byte", typeName));
      case "google.protobuf.DoubleValue" -> nullableSchema(floatingPointSchema("double", typeName));
      case "google.protobuf.Duration" -> durationSchema();
      case "google.protobuf.Empty" -> emptyObjectSchema(typeName);
      case "google.protobuf.FieldMask" -> scalarSchema("string", null, typeName);
      case "google.protobuf.FloatValue" -> nullableSchema(floatingPointSchema("float", typeName));
      case "google.protobuf.Int32Value" -> nullableSchema(scalarSchema("integer", "int32", typeName));
      case "google.protobuf.Int64Value" -> nullableSchema(signedInt64Schema(typeName));
      case "google.protobuf.ListValue" -> listValueSchema();
      case "google.protobuf.StringValue" -> nullableSchema(scalarSchema("string", null, typeName));
      case "google.protobuf.Struct" -> structSchema();
      case "google.protobuf.Timestamp" -> timestampSchema();
      case "google.protobuf.UInt32Value" -> nullableSchema(unsignedInt32Schema(typeName));
      case "google.protobuf.UInt64Value" -> nullableSchema(unsignedInt64Schema(typeName));
      case "google.protobuf.Value" -> JSON_MAPPER.createObjectNode()
        .put("x-protobuf-type", typeName);
      default -> null;
    };
  }

  private static ObjectNode scalarSchema(String type, String format, String protobufType) {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", type);
    if (format != null) {
      schema.put("format", format);
    }
    schema.put("x-protobuf-type", protobufType);
    return schema;
  }

  private static ObjectNode floatingPointSchema(String format, String protobufType) {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("x-protobuf-type", protobufType);
    var alternatives = schema.putArray("anyOf");
    alternatives.addObject()
      .put("type", "number")
      .put("format", format);
    var specialValues = alternatives.addObject()
      .put("type", "string")
      .putArray("enum");
    specialValues.add("NaN");
    specialValues.add("Infinity");
    specialValues.add("-Infinity");
    return schema;
  }

  private static ObjectNode unsignedInt32Schema(String protobufType) {
    return scalarSchema("integer", "int64", protobufType)
      .put("minimum", 0)
      .put("maximum", 4_294_967_295L);
  }

  private static ObjectNode signedInt64Schema(String protobufType) {
    return scalarSchema("string", null, protobufType)
      .put("pattern", "^-?(0|[1-9][0-9]*)$");
  }

  private static ObjectNode unsignedInt64Schema(String protobufType) {
    return scalarSchema("string", null, protobufType)
      .put("pattern", "^(0|[1-9][0-9]*)$");
  }

  private static ObjectNode timestampSchema() {
    return scalarSchema("string", "date-time", "google.protobuf.Timestamp");
  }

  private static ObjectNode durationSchema() {
    return scalarSchema("string", null, "google.protobuf.Duration")
      .put("pattern", "^-?(0|[1-9][0-9]*)(\\.[0-9]{1,9})?s$");
  }

  private static ObjectNode structSchema() {
    return JSON_MAPPER.createObjectNode()
      .put("type", "object")
      .put("additionalProperties", true)
      .put("x-protobuf-type", "google.protobuf.Struct");
  }

  private static ObjectNode listValueSchema() {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "array");
    schema.put("x-protobuf-type", "google.protobuf.ListValue");
    schema.set("items", JSON_MAPPER.createObjectNode());
    return schema;
  }

  private static ObjectNode anySchema() {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", true);
    schema.put("x-protobuf-type", "google.protobuf.Any");
    schema.putObject("properties")
      .putObject("@type")
      .put("type", "string");
    schema.putArray("required").add("@type");
    return schema;
  }

  private static ObjectNode emptyObjectSchema(String protobufType) {
    var schema = JSON_MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.put("x-protobuf-type", protobufType);
    schema.putObject("properties");
    return schema;
  }

  private void collectReferencedSchemaNames(
    TypeSignature typeSignature,
    Deque<String> pending,
    Set<String> generated
  ) {
    if (typeSignature.type() == TypeSignatureType.STRUCT || typeSignature.type() == TypeSignatureType.ENUM) {
      var schemaName = canonicalSchemaNameByTypeName.getOrDefault(typeSignature.signature(), typeSignature.name());
      registerReferencedSchemaName(schemaName, pending, generated);
      return;
    }

    if (typeSignature instanceof MapTypeSignature mapTypeSignature) {
      collectReferencedSchemaNames(mapTypeSignature.valueTypeSignature(), pending, generated);
      return;
    }

    if (typeSignature instanceof ContainerTypeSignature containerTypeSignature) {
      containerTypeSignature.typeParameters().forEach(type ->
        collectReferencedSchemaNames(type, pending, generated));
    }
  }

  private void enqueueReferencedSchemaNames(JsonNode node, Deque<String> pending, Set<String> generated) {
    if (node == null) {
      return;
    }

    if (node.isObject()) {
      var refNode = node.get("$ref");
      if (refNode != null && refNode.isTextual()) {
        var ref = refNode.textValue();
        var prefix = "#/components/schemas/";
        if (ref.startsWith(prefix)) {
          var schemaName = unescapeJsonPointerSegment(ref.substring(prefix.length()));
          registerReferencedSchemaName(schemaName, pending, generated);
        }
      }

      node.elements().forEachRemaining(child -> enqueueReferencedSchemaNames(child, pending, generated));
      return;
    }

    if (node.isArray()) {
      node.elements().forEachRemaining(child -> enqueueReferencedSchemaNames(child, pending, generated));
    }
  }

  private static ObjectNode baseSchema(String typeName) {
    var normalized = typeName.toLowerCase(Locale.ROOT);
    var schema = JSON_MAPPER.createObjectNode();

    switch (normalized) {
      case "boolean", "bool" -> schema.put("type", "boolean");
      case "float" -> schema.setAll(floatingPointSchema("float", "float"));
      case "double", "number" -> schema.setAll(floatingPointSchema("double", "double"));
      case "i", "i8", "i16", "i32", "integer", "int", "int32", "uint32", "sint32",
           "fixed32", "sfixed32" -> {
        schema.put("type", "integer");
        schema.put("format", "int32");
      }
      case "uint64", "fixed64" -> schema.setAll(unsignedInt64Schema(normalized));
      case "i64", "int64", "sint64", "sfixed64", "long", "long64", "l64" ->
        schema.setAll(signedInt64Schema(normalized));
      case "byte", "bytes", "binary" -> {
        schema.put("type", "string");
        schema.put("format", "byte");
      }
      case "string" -> schema.put("type", "string");
      default -> {
        schema.put("type", "object");
        schema.put("additionalProperties", true);
      }
    }

    return schema;
  }

  private static ObjectNode schemaRef(String schemaName) {
    return JSON_MAPPER.createObjectNode()
      .put("$ref", "#/components/schemas/" + escapeJsonPointerSegment(schemaName));
  }

  private static ObjectNode nullableSchema(ObjectNode schema) {
    var wrapper = JSON_MAPPER.createObjectNode();
    wrapper.putArray("anyOf")
      .add(schema)
      .addObject()
      .put("type", "null");
    return wrapper;
  }

  private static ObjectNode schemaWithDescription(ObjectNode schema, String description) {
    if (description == null || description.isBlank()) {
      return schema;
    }

    if (!schema.has("$ref")) {
      schema.put("description", description);
      return schema;
    }

    var wrapper = JSON_MAPPER.createObjectNode();
    wrapper.put("description", description);
    wrapper.putArray("allOf").add(schema);
    return wrapper;
  }

  private static String operationId(ProtoMethodMetadata metadata, int routeIndex) {
    var methodName = metadata.methodName();
    var base = Character.toLowerCase(methodName.charAt(0)) + methodName.substring(1);
    return routeIndex == 0 ? base : base + "Route" + (routeIndex + 1);
  }

  static ObjectNode literalPathItem(ObjectNode paths, String path) {
    var existing = paths.get(path);
    if (existing instanceof ObjectNode objectNode) {
      return objectNode;
    }
    if (existing != null) {
      throw new IllegalStateException("OpenAPI path '%s' is not an object node".formatted(path));
    }

    return paths.putObject(path);
  }

  private static String methodKey(String serviceName, String methodName) {
    return serviceName + '/' + methodName;
  }

  private static boolean isTranscodedService(ServiceInfo serviceInfo) {
    return serviceInfo.name().endsWith(HTTP_SERVICE_SUFFIX);
  }

  private static String baseServiceName(ServiceInfo serviceInfo) {
    return baseServiceName(serviceInfo.name());
  }

  private static String baseServiceName(String serviceName) {
    if (serviceName.endsWith(HTTP_SERVICE_SUFFIX)) {
      return serviceName.substring(0, serviceName.length() - HTTP_SERVICE_SUFFIX.length());
    }

    return serviceName;
  }

  private static String simpleName(String fullName) {
    var lastDot = fullName.lastIndexOf('.');
    return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
  }

  private String docString(String key) {
    return normalizeDocBlock(docStrings.getOrDefault(key, ""));
  }

  private static String firstSentence(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }

    var trimmed = value.trim();
    for (var i = 0; i < trimmed.length(); i++) {
      if (trimmed.charAt(i) == '.' && (i + 1 == trimmed.length() || Character.isWhitespace(trimmed.charAt(i + 1)))) {
        return trimmed.substring(0, i + 1);
      }
    }

    return trimmed;
  }

  private static String firstNonBlank(String... values) {
    for (var value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }

    return "";
  }

  private static String normalizeDocBlock(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }

    return value.lines()
      .map(String::strip)
      .collect(Collectors.joining("\n"))
      .trim();
  }

  private static String appendPath(String baseUrl, String path) {
    if (baseUrl.endsWith("/")) {
      return baseUrl.substring(0, baseUrl.length() - 1) + path;
    }

    return baseUrl + path;
  }

  private static String normalizeBaseUrl(String url) {
    if (url.endsWith("/")) {
      return url.substring(0, url.length() - 1);
    }

    return url;
  }

  private static ServerTemplateDefaults serverTemplateDefaults(String publicAddress) {
    try {
      var uri = URI.create(publicAddress);
      var scheme = Optional.ofNullable(uri.getScheme())
        .map(value -> value.toLowerCase(Locale.ROOT))
        .filter(value -> "http".equals(value) || "https".equals(value))
        .orElse("https");
      var host = Optional.ofNullable(uri.getHost())
        .filter(value -> !value.isBlank())
        .orElse("api.example.com");
      var port = uri.getPort() >= 0
        ? Integer.toString(uri.getPort())
        : switch (scheme) {
          case "http" -> "80";
          case "https" -> "443";
          default -> "443";
        };
      var basePath = Optional.ofNullable(uri.getPath())
        .map(path -> path.startsWith("/") ? path.substring(1) : path)
        .orElse("");
      return new ServerTemplateDefaults(scheme, host, port, basePath);
    } catch (IllegalArgumentException _) {
      return new ServerTemplateDefaults("https", "api.example.com", "443", "v1");
    }
  }

  private static String normalizePath(String path) {
    if (path.startsWith("^")) {
      path = path.substring(1);
    }
    if (path.endsWith("$")) {
      path = path.substring(0, path.length() - 1);
    }
    if (!path.startsWith("/")) {
      path = '/' + path;
    }
    return path;
  }

  private static String normalizeArmeriaPath(String path) {
    return normalizePath(path).replaceAll("/:([A-Za-z_][A-Za-z0-9_]*)", "/{$1}");
  }

  private static String fieldKey(String typeName, String fieldName) {
    return typeName + '/' + fieldName;
  }

  private record ServerTemplateDefaults(String scheme, String host, String port, String basePath) {
  }

  private void markSchemaUsed(String schemaName) {
    usedSchemaNames.add(schemaName);
  }

  private void registerReferencedSchemaName(String schemaName, Deque<String> pending, Set<String> generated) {
    markSchemaUsed(schemaName);
    if (pending != null && generated != null && !generated.contains(schemaName) && !pending.contains(schemaName)) {
      pending.add(schemaName);
    }
  }

  private static boolean isExportedService(String serviceName) {
    return !HEALTH_SERVICE_NAME.equals(serviceName);
  }

  private boolean hasDocumentedUnaryMethods(String serviceName) {
    return protoMethodMetadata.values().stream()
      .anyMatch(metadata -> metadata.serviceName().equals(serviceName) && metadata.unary());
  }

  private static String escapeJsonPointerSegment(String value) {
    return value.replace("~", "~0").replace("/", "~1");
  }

  private static String unescapeJsonPointerSegment(String value) {
    return value.replace("~1", "/").replace("~0", "~");
  }

  private static JsonNode tryReadJson(String value) {
    try {
      return JSON_MAPPER.readTree(value);
    } catch (Exception _) {
      return JSON_MAPPER.getNodeFactory().textNode(value);
    }
  }

  private static ServiceSpecification buildServiceSpecification(List<ServiceConfig> services) {
    var docStrings = new LinkedHashMap<String, DescriptionInfo>();
    var specifications = new ArrayList<ServiceSpecification>();
    var descriptiveTypeInfoProvider = composeDescriptiveTypeInfoProvider();

    for (var plugin : DOC_SERVICE_PLUGINS) {
      var supportedServices = findSupportedServices(plugin, services);
      if (supportedServices.isEmpty()) {
        continue;
      }

      specifications.add(plugin.generateSpecification(supportedServices, DocServiceFilter.ofGrpc(), descriptiveTypeInfoProvider));
      plugin.loadDocStrings(supportedServices).forEach(docStrings::putIfAbsent);
    }

    var merged = ServiceSpecification.merge(specifications, null);
    return new ServiceSpecification(
      merged.services(),
      merged.enums(),
      merged.structs(),
      merged.exceptions(),
      List.of(
        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "Bearer <api-jwt>"),
        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "Basic <base64(username:api-jwt)>")
      ),
      docStrings,
      null
    );
  }

  private static ProtoDescriptorMetadata loadProtoDescriptorMetadata(List<ServiceConfig> services) {
    var methodMetadata = new LinkedHashMap<String, ProtoMethodMetadata>();
    var fieldMetadata = new LinkedHashMap<String, ProtoFieldMetadata>();
    var messageDescriptors = new LinkedHashMap<String, Descriptor>();
    var enumDescriptors = new LinkedHashMap<String, EnumDescriptor>();
    var processedServices = new HashSet<String>();
    var processedFiles = new HashSet<String>();

    for (var serviceConfig : services) {
      var grpcService = serviceConfig.service().as(GrpcService.class);
      if (grpcService == null) {
        continue;
      }

      for (ServerServiceDefinition serviceDefinition : grpcService.services()) {
        var grpcServiceDescriptor = serviceDefinition.getServiceDescriptor();
        if (!processedServices.add(grpcServiceDescriptor.getName())) {
          continue;
        }

        if (!(grpcServiceDescriptor.getSchemaDescriptor() instanceof ProtoFileDescriptorSupplier supplier)) {
          continue;
        }

        FileDescriptor fileDescriptor = supplier.getFileDescriptor();
        collectDescriptorMetadata(
          fileDescriptor,
          fieldMetadata,
          messageDescriptors,
          enumDescriptors,
          processedFiles
        );
        var protoServiceDescriptor = fileDescriptor.getServices().stream()
          .filter(descriptor -> descriptor.getFullName().equals(grpcServiceDescriptor.getName()))
          .findFirst()
          .orElse(null);
        if (protoServiceDescriptor == null) {
          continue;
        }

        for (MethodDescriptor method : protoServiceDescriptor.getMethods()) {
          var apiMethod = method.getOptions().hasExtension(ApiDocsProto.apiMethod)
            ? method.getOptions().getExtension(ApiDocsProto.apiMethod)
            : ApiMethodDocs.getDefaultInstance();
          methodMetadata.putIfAbsent(
            methodKey(protoServiceDescriptor.getFullName(), method.getName()),
            new ProtoMethodMetadata(
              protoServiceDescriptor.getFullName(),
              method.getName(),
              method.getInputType(),
              method.getOutputType(),
              !method.isClientStreaming() && !method.isServerStreaming(),
              List.of(),
              apiMethod.getDisplayName(),
              apiMethod.getDescription(),
              List.copyOf(apiMethod.getPermissionsList()),
              apiMethod.getScope(),
              List.copyOf(apiMethod.getPreconditionsList()),
              apiMethod.getExecution(),
              List.copyOf(apiMethod.getSideEffectsList()),
              httpBindings(method)
            )
          );
        }
      }
    }

    return new ProtoDescriptorMetadata(
      Map.copyOf(methodMetadata),
      Map.copyOf(fieldMetadata),
      Map.copyOf(messageDescriptors),
      Map.copyOf(enumDescriptors)
    );
  }

  private static void collectDescriptorMetadata(
    FileDescriptor fileDescriptor,
    Map<String, ProtoFieldMetadata> fieldMetadata,
    Map<String, Descriptor> messageDescriptors,
    Map<String, EnumDescriptor> enumDescriptors,
    Set<String> processedFiles
  ) {
    if (!processedFiles.add(fileDescriptor.getFullName())) {
      return;
    }

    fileDescriptor.getDependencies().forEach(dependency ->
      collectDescriptorMetadata(dependency, fieldMetadata, messageDescriptors, enumDescriptors, processedFiles));
    fileDescriptor.getEnumTypes().forEach(descriptor ->
      enumDescriptors.putIfAbsent(descriptor.getFullName(), descriptor));
    fileDescriptor.getMessageTypes().forEach(descriptor ->
      collectDescriptorMetadata(descriptor, fieldMetadata, messageDescriptors, enumDescriptors));
  }

  private static void collectDescriptorMetadata(
    Descriptor descriptor,
    Map<String, ProtoFieldMetadata> fieldMetadata,
    Map<String, Descriptor> messageDescriptors,
    Map<String, EnumDescriptor> enumDescriptors
  ) {
    messageDescriptors.putIfAbsent(descriptor.getFullName(), descriptor);
    descriptor.getEnumTypes().forEach(enumDescriptor ->
      enumDescriptors.putIfAbsent(enumDescriptor.getFullName(), enumDescriptor));

    for (FieldDescriptor field : descriptor.getFields()) {
      var apiField = field.getOptions().hasExtension(ApiDocsProto.apiField)
        ? field.getOptions().getExtension(ApiDocsProto.apiField)
        : ApiFieldDocs.getDefaultInstance();
      var configuredBehaviors = field.getOptions().getExtension(FieldBehaviorProto.fieldBehavior);
      var behaviors = configuredBehaviors.isEmpty()
        ? EnumSet.noneOf(FieldBehavior.class)
        : EnumSet.copyOf(configuredBehaviors);
      fieldMetadata.putIfAbsent(
        fieldKey(descriptor.getFullName(), field.getName()),
        new ProtoFieldMetadata(
          field.getJsonName(),
          Set.copyOf(behaviors),
          apiField.getFormat(),
          apiField.getExample(),
          field
        )
      );
    }

    descriptor.getNestedTypes().forEach(nested ->
      collectDescriptorMetadata(nested, fieldMetadata, messageDescriptors, enumDescriptors));
  }

  private static List<ProtoHttpBinding> httpBindings(MethodDescriptor method) {
    if (!method.getOptions().hasExtension(AnnotationsProto.http)) {
      return List.of();
    }

    var bindings = new ArrayList<ProtoHttpBinding>();
    collectHttpBindings(method.getOptions().getExtension(AnnotationsProto.http), bindings);
    return List.copyOf(bindings);
  }

  private static void collectHttpBindings(HttpRule rule, List<ProtoHttpBinding> bindings) {
    if (rule.getPatternCase() != HttpRule.PatternCase.PATTERN_NOT_SET) {
      bindings.add(httpBinding(rule));
    }
    rule.getAdditionalBindingsList().forEach(binding -> collectHttpBindings(binding, bindings));
  }

  private static ProtoHttpBinding httpBinding(HttpRule rule) {
    var method = switch (rule.getPatternCase()) {
      case GET -> "get";
      case PUT -> "put";
      case POST -> "post";
      case DELETE -> "delete";
      case PATCH -> "patch";
      case CUSTOM -> rule.getCustom().getKind().toLowerCase(Locale.ROOT);
      case PATTERN_NOT_SET -> throw new IllegalArgumentException("HTTP rule has no path pattern");
    };
    var path = switch (rule.getPatternCase()) {
      case GET -> rule.getGet();
      case PUT -> rule.getPut();
      case POST -> rule.getPost();
      case DELETE -> rule.getDelete();
      case PATCH -> rule.getPatch();
      case CUSTOM -> rule.getCustom().getPath();
      case PATTERN_NOT_SET -> throw new IllegalArgumentException("HTTP rule has no path pattern");
    };
    return new ProtoHttpBinding(method, normalizePath(path), rule.getBody());
  }

  private static Map<String, StructInfo> createStructLookup(Set<StructInfo> structs) {
    var structLookup = new HashMap<String, StructInfo>();
    for (var struct : structs) {
      structLookup.put(struct.name(), struct);
      if (struct.alias() != null && !struct.alias().equals(struct.name())) {
        structLookup.put(struct.alias(), struct);
      }
    }
    return structLookup;
  }

  private static Map<String, String> createCanonicalSchemaLookup(Set<StructInfo> structs, Set<EnumInfo> enums) {
    var schemaLookup = new HashMap<String, String>();
    structs.forEach(struct -> {
      schemaLookup.put(struct.name(), struct.name());
      if (struct.alias() != null && !struct.alias().equals(struct.name())) {
        schemaLookup.put(struct.alias(), struct.name());
      }
    });
    enums.forEach(enumInfo -> schemaLookup.put(enumInfo.name(), enumInfo.name()));
    return schemaLookup;
  }

  private static Set<ServiceConfig> findSupportedServices(DocServicePlugin plugin, List<ServiceConfig> services) {
    var supportedServiceTypes = plugin.supportedServiceTypes();
    return services.stream()
      .filter(serviceConfig -> supportedServiceTypes.stream().anyMatch(type -> serviceConfig.service().as(type) != null))
      .collect(Collectors.toSet());
  }

  private static DescriptiveTypeInfoProvider composeDescriptiveTypeInfoProvider() {
    return typeDescriptor -> {
      for (var provider : DESCRIPTIVE_TYPE_INFO_PROVIDERS) {
        var descriptiveTypeInfo = provider.newDescriptiveTypeInfo(typeDescriptor);
        if (descriptiveTypeInfo != null) {
          return descriptiveTypeInfo;
        }
      }

      return null;
    };
  }

  private static <T> List<T> immutableServiceLoader(Class<T> type) {
    return List.copyOf(StreamSupport.stream(ServiceLoader.load(type, type.getClassLoader()).spliterator(), false).toList());
  }

  private record ProtoDescriptorMetadata(
    Map<String, ProtoMethodMetadata> methodMetadata,
    Map<String, ProtoFieldMetadata> fieldMetadata,
    Map<String, Descriptor> messageDescriptors,
    Map<String, EnumDescriptor> enumDescriptors
  ) {
  }

  private record ProtoMethodMetadata(
    String serviceName,
    String methodName,
    Descriptor inputDescriptor,
    Descriptor outputDescriptor,
    boolean unary,
    List<String> declaredExceptions,
    String displayName,
    String description,
    List<String> permissions,
    String scope,
    List<String> preconditions,
    String execution,
    List<String> sideEffects,
    List<ProtoHttpBinding> httpBindings
  ) {
    private boolean hasHttpBindings() {
      return !httpBindings.isEmpty();
    }
  }

  private record ProtoHttpBinding(
    String method,
    String path,
    String body
  ) {
  }

  private record ProtoFieldMetadata(
    String jsonName,
    Set<FieldBehavior> behaviors,
    String format,
    String example,
    FieldDescriptor descriptor
  ) {
  }
}
