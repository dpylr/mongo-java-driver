/*
 * Copyright (c) 2008-2015 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bson.codecs.configuration.mapper;

import com.fasterxml.classmate.AnnotationConfiguration.StdConfiguration;
import com.fasterxml.classmate.AnnotationInclusion;
import com.fasterxml.classmate.MemberResolver;
import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.ResolvedTypeWithMembers;
import com.fasterxml.classmate.TypeResolver;
import com.fasterxml.classmate.members.ResolvedField;
import com.fasterxml.classmate.members.ResolvedMethod;
import org.bson.codecs.configuration.CodecRegistry;

import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import static java.util.Arrays.asList;

/**
 * This class represents the various generics and field metadata of a class for use in mapping data to and from the database.
 */
@SuppressWarnings("Since15")
public final class ClassModel extends MappedType {
    private final Map<String, FieldModel> fields = new TreeMap<String, FieldModel>();
    private final CodecRegistry registry;
    private final TypeResolver resolver;
    private final MemberResolver memberResolver;
    private final WeightedValue<String> collectionName;
    private final Map<String, List<MethodModel>> methods = new TreeMap<String, List<MethodModel>>();
    private boolean mapped;
    private final  Map<String, Object> parameterizedTypes = new HashMap<String, Object>();
    private final List<TypeVariable<?>> typeParameters = new ArrayList<TypeVariable<?>>();

    /**
     * Construct a ClassModel for the given Classs.
     *
     * @param registry the registry to use for deferred lookups for codecs for the fields.
     * @param resolver the TypeResolver used in discovery of Class metatadata
     * @param aClass   the Class to model
     */
    public ClassModel(final CodecRegistry registry, final TypeResolver resolver, final Class<?> aClass) {
        super(aClass);
        this.registry = registry;
        this.resolver = resolver;
        collectionName = new WeightedValue<String>();
        memberResolver = new MemberResolver(resolver);
        try {
            aClass.getConstructor().setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ClassMappingException(e.getMessage(), e);
        }
    }

    public ClassModel(final ClassModel classModel, final List<Class<?>> parameterTypes) {
        //throw new UnsupportedOperationException("Not implemented yet!");
        super(classModel.getType());
        this.registry = classModel.registry;
        this.resolver = classModel.resolver;
        this.memberResolver = classModel.memberResolver;
        this.collectionName = classModel.collectionName;
        classModel.map();

        final TypeVariable<Class<Object>>[] typeVariables = classModel.getType().getTypeParameters();
        final Map<String, Class<?>> typeMap = new HashMap<String, Class<?>>();
        for (int i = 0; i < typeVariables.length; i++) {
            typeMap.put(typeVariables[i].getTypeName(), parameterTypes.get(i));
        }
        for (Entry<String, FieldModel> entry : classModel.fields.entrySet()) {
            FieldModel model = new FieldModel(entry.getValue(), typeMap);
            fields.put(model.getName(), model);
        }
    }

    /**
     * Returns the collection name to use when de/encoding BSON documents.
     *
     * @return the collection name
     */
    public String getCollectionName() {
        return collectionName.get();
    }

    /**
     * Retrieves a specific field from the model.
     *
     * @param name the field's name
     * @return the field
     */
    public FieldModel getField(final String name) {
        return fields.get(name);
    }

    /**
     * Returns all the fields on this model
     *
     * @return the list of fields
     */
    public List<FieldModel> getFields() {
        return new ArrayList<FieldModel>(fields.values());
    }

    /**
     * Returns the name of the class represented by this ClassModel
     *
     * @return the name
     */
    public String getName() {
        return getType().getSimpleName();
    }

    /**
     * Executes the actual mapping of the class.
     */
    public void map() {
        if (!mapped) {
            final ResolvedType resolved = resolver.resolve(getType());
            final ResolvedTypeWithMembers type =
                memberResolver.resolve(resolved, new StdConfiguration(AnnotationInclusion.INCLUDE_AND_INHERIT_IF_INHERITED), null);
            typeParameters.addAll(asList(resolved.getErasedType().getTypeParameters()));
            final List<ResolvedType> typeParameters1 = resolved.getTypeParameters();
            for (final ResolvedType resolvedType : typeParameters1) {
                addParameter(resolvedType.getErasedType());
            }

            for (final ResolvedField field : type.getMemberFields()) {
                addField(field);
            }

            for (final ResolvedMethod memberMethod : type.getMemberMethods()) {
                addMethod(memberMethod);
            }
            mapped = true;
        }
    }

    public Object resolveGenericType(final String typeName) {
        return parameterizedTypes.get(typeName);
    }

    private void addField(final ResolvedField field) {
        addField(new FieldModel(this, registry, field));
    }

    private void addMethod(final ResolvedMethod method) {
        final MethodModel model;
        try {
            model = new MethodModel(this, registry, method);
            final List<MethodModel> list = getMethods(model.getName());
            if (list.isEmpty()) {
                methods.put(model.getName(), list);
            }
            list.add(model);
        } catch (final Exception e) {
            throw new ClassMappingException(e.getMessage(), e);
        }
    }

    /**
     * Adds a new FieldModel to this ClassModel.  This is useful for transformative conventions such encrypting conventions than need to
     * operate in response to mappings on other FieldModels.
     *
     * @param model the model to add
     */
    public void addField(final FieldModel model) {
        fields.put(model.getName(), model);
    }

    /**
     * Gets the named method if it exists
     *
     * @param name the name of the method to fetch
     * @return the MethodModel named
     */
    public List<MethodModel> getMethods(final String name) {
        final List<MethodModel> list = methods.get(name);
        return list == null ? new ArrayList<MethodModel>() : list;
    }

    /**
     * Suggests a new value for the collection name.
     *
     * @param weight         The weight to give this value relative to other values set
     * @param collectionName the new collection name to suggest
     * @see WeightedValue
     */
    public void setCollectionName(final Integer weight, final String collectionName) {
        this.collectionName.set(weight, collectionName);
    }

}
