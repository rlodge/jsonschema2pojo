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
import com.sun.codemodel.JClassContainer;
import com.sun.codemodel.JType;
import org.jsonschema2pojo.Schema;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A simple interpretation of allOf rules.  It doesn't reflect the full expressive capacity of JSON schema.  It does:
 * - handle "properties" and "required"
 * - treat a single $ref as a superclass or dereference properties from that $ref
 */
public class OneOfRule implements Rule<JClassContainer, JType> {

	private final RuleFactory ruleFactory;

	public OneOfRule(final RuleFactory ruleFactory) {
		this.ruleFactory = ruleFactory;
	}

	@Override
	public JType apply(
		final String nodeName, final JsonNode node, final JsonNode parent, final JClassContainer jClass, final Schema currentSchema
	) {
		if (node != null && node.isArray()) {
			List<JsonNode> elements = new ArrayList<>();
			node.elements().forEachRemaining(elements::add);

			final long howManyRefs = elements.stream().filter(e -> e.has("$ref")).count();

			if (elements.size() != howManyRefs) {
				throw new IllegalArgumentException("We only handle 'oneOf' as a list of refs pointing to the same superclass");
			}

			final List<JClass> classesInOneOf = elements.stream()
				.map(element -> {
					final SchemaNodePair resolvedRef = resolveRefs(new SchemaNodePair(element, currentSchema, Optional.empty()));
					JType refType;
					if (resolvedRef.refSchema.get().isGenerated()) {
						refType = resolvedRef.refSchema.get().getJavaType();
					} else {
						refType = ruleFactory.getSchemaRule().apply(nodeName, element, parent, jClass, currentSchema);
					}
					if (!(refType instanceof JClass)) {
						throw new IllegalArgumentException("We don't know what to do with oneOf lists that don't resolve to JClass entities");
					}
					return (JClass) refType;
				})
				.collect(Collectors.toList());

			final Optional<List<JClass>> reduce = classesInOneOf.stream()
				.map(refType -> {
					final Set<JClass> classHierarchy = new LinkedHashSet<>();
					getClassHierarchy(refType, classHierarchy);
					getInterfaces(refType, classHierarchy);
					return (List<JClass>) new ArrayList<>(classHierarchy);
				})
				.reduce((l, r) -> {
					l.retainAll(r);
					return l;
				});

			final JClass determinedSuperclass;
			if (reduce.isPresent() && reduce.get().size() > 0) {
				determinedSuperclass = reduce.get().get(0);
			} else {
				throw new IllegalArgumentException("Couldn't find any common superclasses");
			}
			classesInOneOf
				.forEach(refType -> {
					ruleFactory.getAnnotator().addJsonSubtypesAndTypeInfo(determinedSuperclass, refType, ruleFactory.getGenerationConfig().getDeserializationClassProperty());
				});
			return determinedSuperclass;
		}

		throw new IllegalArgumentException("We don't know what to do with oneOfs that aren't arrays or are null");
	}

	private void getClassHierarchy(JClass cls, Set<JClass> classes) {
		final JClass superClass = cls._extends();
		if (superClass != null && !superClass.fullName().startsWith("java")) {
			classes.add(superClass);
			getClassHierarchy(superClass, classes);
		}
	}

	private void getInterfaces(JClass cls, Set<JClass> classes) {
		cls._implements().forEachRemaining(i -> {
			if (!i.fullName().startsWith("java")) {
				classes.add(i);
			}
		});
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
