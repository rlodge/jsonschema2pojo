/**
 * Copyright © 2010-2017 Nokia
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jsonschema2pojo.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JType;
import org.jsonschema2pojo.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A simple interpretation of allOf rules.  It doesn't reflect the full expressive capacity of JSON schema.  It does:
 * - handle "properties" and "required"
 * - treat a single $ref as a superclass or dereference properties from that $ref
 */
public class AllOfRule implements Rule<JDefinedClass, JDefinedClass> {

	private final RuleFactory ruleFactory;

	public AllOfRule(final RuleFactory ruleFactory) {
		this.ruleFactory = ruleFactory;
	}

	@Override
	public JDefinedClass apply(
		final String nodeName, final JsonNode node, final JsonNode parent, final JDefinedClass jClass, final Schema currentSchema
	) {
		if (node != null && node.isArray()) {
			List<JsonNode> elements = new ArrayList<>();
			node.elements().forEachRemaining(elements::add);

			final long howManyRefs = elements.stream().filter(e -> e.has("$ref")).count();

			for (JsonNode element : elements) {
				if (element.isObject()) {
					if (element.has("$ref")) {
						final SchemaNodePair resolvedRef = resolveRefs(new SchemaNodePair(element, currentSchema, Optional.empty()));
						if (howManyRefs == 1 && resolvedRef.refSchema.isPresent()) {
							JType refType;
							if (resolvedRef.refSchema.get().isGenerated()) {
								refType = resolvedRef.refSchema.get().getJavaType();
							} else {
								refType = ruleFactory.getSchemaRule().apply(nodeName, element, parent, jClass, currentSchema);
							}
							if (refType instanceof JClass) {
								handleExtension(jClass, resolvedRef);
							} else {
								handleElement(nodeName, node, jClass, currentSchema, resolvedRef.node);
							}
						} else {
							handleElement(nodeName, node, jClass, currentSchema, resolvedRef.node);
						}
					} else {
						handleElement(nodeName, node, jClass, currentSchema, element);
					}
				}
			}
		}
		return jClass;
	}

	private void handleExtension(final JDefinedClass jClass, final SchemaNodePair resolvedRef) {
		final JClass superClass = (JClass) resolvedRef.refSchema.get().getJavaType();
		jClass._extends(superClass);
		ruleFactory.getAnnotator().addJsonSubtypesAndTypeInfo(superClass, jClass, ruleFactory.getGenerationConfig().getDeserializationClassProperty());
	}

	private void handleElement(final String nodeName, final JsonNode node, final JDefinedClass jClass, final Schema currentSchema, final JsonNode element) {
		try {
			if (element.has("properties")) {
				ruleFactory.getPropertiesRule().apply(nodeName, element.get("properties"), node, jClass, currentSchema);
				ruleFactory.getDynamicPropertiesRule().apply(nodeName, element.get("properties"), node, jClass, currentSchema);
			}
			if (element.has("required")) {
				ruleFactory.getPropertiesRule().apply(nodeName, element.get("required"), node, jClass, currentSchema);
			}
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException("Error generating properties; failure on " + node, e);
		}
	}

	private SchemaNodePair resolveRefs(SchemaNodePair pair) {
		if (pair.node.has("$ref")) {
			Schema refSchema = ruleFactory.getSchemaStore().create(pair.parent, pair.node.get("$ref").asText(), ruleFactory.getGenerationConfig().getRefFragmentPathDelimiters());
			JsonNode refNode = refSchema.getContent();
			return resolveRefs(new SchemaNodePair(refNode, pair.parent, Optional.of(refSchema)));
		} else {
			return pair;
		}
	}

	@SuppressWarnings({"WeakerAccess", "OptionalUsedAsFieldOrParameterType"})
	private static class SchemaNodePair {

		public final JsonNode node;
		public final Schema parent;
		public final Optional<Schema> refSchema;

		private SchemaNodePair(final JsonNode node, final Schema parent, Optional<Schema> refSchema) {
			this.node = node;
			this.parent = parent;
			this.refSchema = refSchema;
		}
	}

}
