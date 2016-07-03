/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *	 http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.api.java.typeutils.runtime;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeutils.TypeSerializer;

import org.apache.flink.util.InstantiationUtil;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.api.java.typeutils.PojoTypeInfo.accesStringForField;
import static org.apache.flink.api.java.typeutils.PojoTypeInfo.modifyStringForField;

public final class PojoSerializerGenerator<T> {
	private static final Map<Class<?>, Class<TypeSerializer<?>>> generatedClasses = new HashMap<>();
	private static final String packageName = "org.apache.flink.api.java.typeutils.runtime.generated";

	private final Class<T> clazz;
	private final Field[] refFields;
	private final TypeSerializer<?>[] fieldSerializers;
	private final ExecutionConfig config;
	private String code;

	public PojoSerializerGenerator(
		Class<T> clazz,
		TypeSerializer<?>[] fields,
		Field[] reflectiveFields,
		ExecutionConfig config) {
		this.clazz = checkNotNull(clazz);
		this.refFields = checkNotNull(reflectiveFields);
		this.fieldSerializers = checkNotNull(fields);
		this.config = checkNotNull(config);
		for (int i = 0; i < this.refFields.length; i++) {
			this.refFields[i].setAccessible(true);
		}
	}

	public TypeSerializer<T> createSerializer()  {
		final String className = clazz.getSimpleName() + "_GeneratedSerializer";
		try {
			Class<TypeSerializer<?>> serializerClazz;
			if (generatedClasses.containsKey(clazz)) {
				serializerClazz = generatedClasses.get(clazz);
			} else {
				generateCode(className);
				serializerClazz = InstantiationUtil.compile(clazz.getClassLoader(), packageName + "." + className, code);
				generatedClasses.put(clazz, serializerClazz);
			}
			Constructor<?>[] ctors = serializerClazz.getConstructors();
			assert ctors.length == 1;
			return (TypeSerializer<T>)ctors[0].newInstance(new Object[]{clazz, fieldSerializers, config});
		}
		catch (Exception e) {
			throw new RuntimeException("Unable to generate serializer: " + className, e);
		}
	}

	private void generateCode(String className) {
		assert fieldSerializers.length > 0;
		String typeName = clazz.getCanonicalName();
		StringBuilder members = new StringBuilder();
		for (int i = 0; i < fieldSerializers.length; ++i) {
			members.append(String.format("final TypeSerializer f%d;\n", i));
		}
		StringBuilder initMembers = new StringBuilder();
		for (int i = 0; i < fieldSerializers.length; ++i) {
			initMembers.append(String.format("f%d = serializerFields[%d];\n", i, i));
		}
		StringBuilder createFields = new StringBuilder();
		for (int i = 0; i < fieldSerializers.length; ++i) {
			createFields.append(String.format("((" + typeName + ")t)." + modifyStringForField(refFields[i],
				"f%d.createInstance()") + ";\n", i));
		}
		StringBuilder copyFields = new StringBuilder();
		copyFields.append("Object value;\n");
		for (int i = 0; i < fieldSerializers.length; ++i) {
			if (refFields[i].getType().isPrimitive()) {
				copyFields.append(String.format("((" + typeName + ")target)." + modifyStringForField(refFields[i],
					"f%d.copy(((" + typeName + ")from)." + accesStringForField(refFields[i])) + ");\n", i));
			} else {
				copyFields.append(String.format(
					"value = ((" + typeName + ")from)." + accesStringForField(refFields[i]) + ";\n" +
					"if (value != null) {\n" +
					"	((" + typeName + ")target)." + modifyStringForField(refFields[i], "f%d.copy(value)") + ";\n" +
					"} else {\n" +
					"	((" + typeName + ")target)." + modifyStringForField(refFields[i], "null") + ";\n" +
					"}\n", i));
			}
		}
		StringBuilder reuseCopyFields = new StringBuilder();
		reuseCopyFields.append("Object value;\n");
		reuseCopyFields.append("Object reuseValue;\n");
		reuseCopyFields.append("Object copy;\n");
		for (int i = 0; i < fieldSerializers.length; ++i) {
			if (refFields[i].getType().isPrimitive()) {
				reuseCopyFields.append(String.format("((" + typeName + ")reuse)." + modifyStringForField(refFields[i],
					"f%d.copy(((" + typeName + ")from)." + accesStringForField(refFields[i]) + ")") + ";\n", i));
			} else {
				reuseCopyFields.append(String.format(
					"value = ((" + typeName + ")from)." + accesStringForField(refFields[i]) + ";\n" +
					"if (value != null) {\n" +
					"	reuseValue = ((" + typeName + ")reuse)." + accesStringForField(refFields[i]) + ";\n" +
					"	if (reuseValue != null) {\n" +
					"		copy = f%d.copy(value, reuseValue);\n" +
					"	} else {\n" +
					"		copy = f%d.copy(value);\n" +
					"	}\n" +
					"	((" + typeName + ")reuse)." + modifyStringForField(refFields[i], "copy") + ";\n" +
					"} else {\n" +
					"	((" + typeName + ")reuse)." + modifyStringForField(refFields[i], "null") + ";\n" +
					"}\n", i, i));
			}
		}
		StringBuilder memberHash = new StringBuilder();
		for (int i = 0; i < fieldSerializers.length; ++i) {
			memberHash.append(String.format(" f%d,", i));
		}
		memberHash.deleteCharAt(memberHash.length() - 1);
		StringBuilder memberEquals = new StringBuilder();
		for (int i = 0; i < fieldSerializers.length; ++i) {
			memberEquals.append(String.format("Objects.equals(this.f%d, other.f%d) && ", i, i));
		}
		memberEquals.delete(memberEquals.length() - 3, memberEquals.length());
		StringBuilder serializeFields = new StringBuilder();
		serializeFields.append("Object o;\n");
		for (int i = 0; i < fieldSerializers.length; ++i) {
			if (refFields[i].getType().isPrimitive()) {
				serializeFields.append(String.format(
					"target.writeBoolean(false);\n" +
					"f%d.serialize(((" + typeName + ")value)." + accesStringForField(refFields[i]) + ", target);\n",
					i));
			} else {
				serializeFields.append(String.format(
					"o = ((" + typeName + ")value)." + accesStringForField(refFields[i]) + ";\n" +
					"if (o == null) {\n" +
					"	target.writeBoolean(true);\n" +
					"} else {\n" +
					"	target.writeBoolean(false);\n" +
					"	f%d.serialize(o, target);\n" +
					"}\n", i));
			}
		}
		StringBuilder deserializeFields = new StringBuilder();
		deserializeFields.append("boolean isNull;\n");
		for (int i = 0; i < fieldSerializers.length; ++i) {
			if (refFields[i].getType().isPrimitive()) {
				deserializeFields.append(String.format("source.readBoolean();\n" +
					"((" + typeName + ")target)." + modifyStringForField(refFields[i], "f%d.deserialize(source)") +
					";\n", i));
			} else {
				deserializeFields.append(String.format("isNull = source.readBoolean();\n" +
					"if (isNull) {\n" +
					"	((" + typeName + ")target)." + modifyStringForField(refFields[i], "null") + ";\n" +
					"} else {\n" +
					"	((" + typeName + ")target)." + modifyStringForField(refFields[i], "f%d.deserialize(source)") +
					";\n" +
					"}\n", i));
			}
		}
		StringBuilder reuseDeserializeFields = new StringBuilder();
		reuseDeserializeFields.append("boolean isNull;\n");
		reuseDeserializeFields.append("Object field;\n");
		reuseDeserializeFields.append("Object reuseField;\n");
		for (int i = 0; i < fieldSerializers.length; ++i) {
			if (refFields[i].getType().isPrimitive()) {
				reuseDeserializeFields .append(String.format("source.readBoolean();\n" +
					"((" + typeName + ")reuse)." + modifyStringForField(refFields[i], "f%d.deserialize(source)") +
					";\n", i));
			} else {
				reuseDeserializeFields .append(String.format("isNull = source.readBoolean();\n" +
					"if (isNull) {\n" +
					"	((" + typeName + ")reuse)." + modifyStringForField(refFields[i], "null") + ";\n" +
					"} else {\n" +
					"	reuseField = ((" + typeName + ")reuse)." + accesStringForField(refFields[i]) + ";\n" +
					"	if (reuseField != null) {\n" +
					"		field = f%d.deserialize(reuseField, source);\n" +
					"	} else {\n" +
					"		field = f%d.deserialize(source);\n" +
					"	}\n" +
					"	((" + typeName + ")reuse)." + modifyStringForField(refFields[i], "field") + ";\n" +
					"}\n", i, i, i, i, i));
			}
		}
		StringBuilder dataCopyFields = new StringBuilder();
		dataCopyFields.append("boolean isNull;\n");
		for (int i = 0; i < fieldSerializers.length; ++i) {
			if (refFields[i].getType().isPrimitive()) {
				dataCopyFields.append(String.format("source.readBoolean();\n" +
					"target.writeBoolean(false);\n" +
					"f%d.copy(source, target);\n", i));
			} else {
				dataCopyFields.append(String.format("isNull = source.readBoolean();\n" +
					"target.writeBoolean(isNull);\n" +
					"if (!isNull) {\n" +
					"	f%d.copy(source, target);\n" +
					"}\n", i));
			}
		}
		StringBuilder duplicateSerializers = new StringBuilder();
		for (int i = 0; i < fieldSerializers.length; ++i) {
			duplicateSerializers.append(String.format("duplicateFieldSerializers[%d] = f%d.duplicate();\n" +
				"if (duplicateFieldSerializers[%d] != f%d) {\n" +
				"	stateful = true;\n" +
				"}\n", i, i, i, i));
		}
		Map<String, String> root = new HashMap<>();
		root.put("isFinal", Boolean.toString(Modifier.isFinal(clazz.getModifiers())));
		root.put("packageName", packageName);
		root.put("className", className);
		root.put("typeName", typeName);
		root.put("members", members.toString());
		root.put("initMembers", initMembers.toString());
		root.put("createFields", createFields.toString());
		root.put("copyFields", copyFields.toString());
		root.put("reuseCopyFields", reuseCopyFields.toString());
		root.put("memberHash", memberHash.toString());
		root.put("memberEquals", memberEquals.toString());
		root.put("serializeFields", serializeFields.toString());
		root.put("deserializeFields", deserializeFields.toString());
		root.put("reuseDeserializeFields", reuseDeserializeFields.toString());
		root.put("dataCopyFields", dataCopyFields.toString());
		root.put("duplicateSerializers", duplicateSerializers.toString());
		try {
			code = InstantiationUtil.getCodeFromTemplate("PojoSerializerTemplate.ftl", root);
		} catch (IOException e) {
			throw new RuntimeException("Unable to read template.", e);
		}
	}
}

