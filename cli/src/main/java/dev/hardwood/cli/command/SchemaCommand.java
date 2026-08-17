/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Mixin;
import org.aesh.command.option.Option;

import dev.hardwood.InputFile;
import dev.hardwood.cli.internal.AvroNames;
import dev.hardwood.cli.internal.JsonStrings;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.schema.SchemaNode;

@CommandDefinition(name = "schema", description = "Print the file schema.", generateHelp = true)
public class SchemaCommand implements Command<CommandInvocation> {

    enum Format {
        NATIVE,
        AVRO,
        PROTO
    }

    @Mixin
    FileMixin fileMixin;

    @Option(shortName = 'F', name = "format", defaultValue = "NATIVE", description = "Output format: NATIVE (default), AVRO, PROTO.")
    Format format;

    @Override
    public CommandResult execute(CommandInvocation ci) {
        InputFile inputFile = fileMixin.toInputFile();
        if (inputFile == null) {
            return CommandResult.FAILURE;
        }

        try (ParquetFileReader reader = ParquetFileReader.open(inputFile)) {
            FileSchema schema = reader.getFileSchema();

            String output = switch (format) {
                case NATIVE -> schema.toString();
                case AVRO -> toAvroSchema(schema);
                case PROTO -> toProtoSchema(schema);
            };

            System.out.println(output);
        }
        catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            return CommandResult.FAILURE;
        }

        return CommandResult.SUCCESS;
    }

    // ── Avro ─────────────────────────────────────────────────────────────────

    private static String toAvroSchema(FileSchema schema) {
        StringBuilder sb = new StringBuilder();
        appendAvroRecord(sb, schema.getRootNode(), schema.getName(), 0);
        return sb.toString();
    }

    private static void appendAvroRecord(StringBuilder sb, SchemaNode.GroupNode group, String name, int indent) {
        String p = "  ".repeat(indent);
        sb.append(p).append("{\n");
        sb.append(p).append("  \"type\": \"record\",\n");
        sb.append(p).append("  \"name\": \"").append(AvroNames.sanitize(capitalize(name))).append("\",\n");
        String doc = avroDoc(name);
        if (!doc.isEmpty()) {
            sb.append(p).append("  ").append(doc).append("\n");
        }
        sb.append(p).append("  \"fields\": [\n");

        List<SchemaNode> children = group.children();
        Set<String> usedNames = new HashSet<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            SchemaNode child = children.get(i);
            appendAvroField(sb, child, disambiguate(AvroNames.sanitize(child.name()), usedNames), indent + 2);
            if (i < children.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append(p).append("  ]\n");
        sb.append(p).append("}");
    }

    private static void appendAvroField(StringBuilder sb, SchemaNode node, String avroName, int indent) {
        boolean optional = node.repetitionType() == RepetitionType.OPTIONAL;
        String p = "  ".repeat(indent);
        sb.append(p).append("{ \"name\": \"").append(avroName).append("\", ");
        String doc = avroDoc(node.name());
        if (!doc.isEmpty()) {
            sb.append(doc).append(" ");
        }
        sb.append("\"type\": ");
        appendAvroType(sb, node, optional, indent);
        if (optional) {
            sb.append(", \"default\": null");
        }
        sb.append(" }");
    }

    /// Returns a `doc` attribute carrying the Parquet name whenever that name does not
    /// survive the mapping onto the Avro name grammar, so the rewrite is visible rather
    /// than silent. Compared against the sanitized name only: the capitalization applied
    /// to record names is cosmetic and does not warrant a note.
    private static String avroDoc(String parquetName) {
        if (parquetName.equals(AvroNames.sanitize(parquetName))) {
            return "";
        }
        return "\"doc\": \"Parquet name: " + JsonStrings.escape(parquetName) + "\",";
    }

    /// Sanitizing is not injective, so two Parquet names can map onto the same Avro name.
    /// Avro rejects a record with duplicate field names, hence the numeric suffix.
    private static String disambiguate(String avroName, Set<String> usedNames) {
        String candidate = avroName;
        for (int suffix = 2; !usedNames.add(candidate); suffix++) {
            candidate = avroName + "_" + suffix;
        }
        return candidate;
    }

    private static void appendAvroType(StringBuilder sb, SchemaNode node, boolean optional, int indent) {
        if (optional) {
            sb.append("[\"null\", ");
        }
        switch (node) {
            case SchemaNode.PrimitiveNode prim -> sb.append("\"").append(primitiveToAvroType(prim)).append("\"");
            case SchemaNode.GroupNode group when group.isList() -> {
                SchemaNode elem = group.getListElement();
                String itemType = elem instanceof SchemaNode.PrimitiveNode prim ? primitiveToAvroType(prim) : "string";
                sb.append("{\"type\": \"array\", \"items\": \"").append(itemType).append("\"}");
            }
            case SchemaNode.GroupNode group when group.isMap() -> sb.append("{\"type\": \"map\", \"values\": \"string\"}");
            case SchemaNode.GroupNode group -> {
                sb.append("\n");
                appendAvroRecord(sb, group, group.name(), indent);
            }
        }
        if (optional) {
            sb.append("]");
        }
    }

    private static String primitiveToAvroType(SchemaNode.PrimitiveNode prim) {
        return switch (prim.type()) {
            case BOOLEAN -> "boolean";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case INT32 -> {
                boolean unsignedInt = prim.logicalType() instanceof LogicalType.IntType it && !it.isSigned() && it.bitWidth() == 32;
                yield unsignedInt ? "long" : "int";
            }
            case INT64, INT96 -> "long";
            case BYTE_ARRAY -> prim.logicalType() instanceof LogicalType.StringType
                    || prim.logicalType() instanceof LogicalType.EnumType
                    || prim.logicalType() instanceof LogicalType.JsonType ? "string" : "bytes";
            case FIXED_LEN_BYTE_ARRAY -> "bytes";
        };
    }

    // ── Proto ─────────────────────────────────────────────────────────────────

    private static String toProtoSchema(FileSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("syntax = \"proto3\";\n\n");
        appendProtoMessage(sb, schema.getRootNode(), 0);
        return sb.toString();
    }

    private static void appendProtoMessage(StringBuilder sb, SchemaNode.GroupNode group, int indent) {
        String p = "  ".repeat(indent);
        sb.append(p).append("message ").append(capitalize(group.name())).append(" {\n");

        List<SchemaNode.GroupNode> nestedStructs = new ArrayList<>();
        int fieldNum = 1;

        for (SchemaNode child : group.children()) {
            fieldNum = appendProtoField(sb, child, fieldNum, indent + 1, nestedStructs);
        }

        for (SchemaNode.GroupNode nested : nestedStructs) {
            sb.append("\n");
            appendProtoMessage(sb, nested, indent + 1);
        }

        sb.append(p).append("}\n");
    }

    private static int appendProtoField(StringBuilder sb, SchemaNode node, int fieldNum, int indent,
                                        List<SchemaNode.GroupNode> nestedStructs) {
        String p = "  ".repeat(indent);
        switch (node) {
            case SchemaNode.PrimitiveNode prim -> {
                String mod = prim.repetitionType() == RepetitionType.OPTIONAL ? "optional " : "";
                sb.append(p).append(mod).append(primitiveToProtoType(prim))
                        .append(" ").append(prim.name()).append(" = ").append(fieldNum).append(";\n");
            }
            case SchemaNode.GroupNode group when group.isList() -> {
                SchemaNode elem = group.getListElement();
                String protoType = elem instanceof SchemaNode.PrimitiveNode prim ? primitiveToProtoType(prim) : capitalize(elem.name());
                sb.append(p).append("repeated ").append(protoType)
                        .append(" ").append(group.name()).append(" = ").append(fieldNum).append(";\n");
            }
            case SchemaNode.GroupNode group when group.isMap() -> sb.append(p).append("map<string, string> ")
                    .append(group.name()).append(" = ").append(fieldNum).append(";\n");
            case SchemaNode.GroupNode group -> {
                sb.append(p).append(capitalize(group.name())).append(" ")
                        .append(group.name()).append(" = ").append(fieldNum).append(";\n");
                nestedStructs.add(group);
            }
        }
        return fieldNum + 1;
    }

    private static String primitiveToProtoType(SchemaNode.PrimitiveNode prim) {
        return switch (prim.type()) {
            case BOOLEAN -> "bool";
            case INT32 -> prim.logicalType() instanceof LogicalType.IntType it && !it.isSigned() ? "uint32" : "int32";
            case INT64, INT96 -> prim.logicalType() instanceof LogicalType.IntType it && !it.isSigned() ? "uint64" : "int64";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case BYTE_ARRAY -> prim.logicalType() instanceof LogicalType.StringType
                    || prim.logicalType() instanceof LogicalType.EnumType
                    || prim.logicalType() instanceof LogicalType.JsonType ? "string" : "bytes";
            case FIXED_LEN_BYTE_ARRAY -> prim.logicalType() instanceof LogicalType.UuidType ? "string" : "bytes";
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
