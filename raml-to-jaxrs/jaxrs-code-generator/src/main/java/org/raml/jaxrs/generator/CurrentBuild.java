/*
 * Copyright 2013-2017 (c) MuleSoft, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.raml.jaxrs.generator;

import com.google.common.base.Function;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.FluentIterable;
import com.google.common.io.Files;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import com.sun.codemodel.JCodeModel;
import org.apache.commons.io.FileUtils;
import org.jsonschema2pojo.GenerationConfig;
import org.raml.jaxrs.generator.builders.*;
import org.raml.jaxrs.generator.builders.resources.ResourceGenerator;
import org.raml.jaxrs.generator.extension.resources.*;
import org.raml.jaxrs.generator.ramltypes.GMethod;
import org.raml.jaxrs.generator.ramltypes.GResource;
import org.raml.jaxrs.generator.ramltypes.GResponse;
import org.raml.jaxrs.generator.v10.*;
import org.raml.ramltopojo.ResultingPojos;
import org.raml.v2.api.model.v10.api.Api;

import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.singletonList;

/**
 * Created by Jean-Philippe Belanger on 10/26/16. The art of building stuff is here. Factory for building root stuff.
 */
public class CurrentBuild {

  private final GFinder typeFinder;
  private final Api api;
  private ExtensionManager extensionManager;
  private final File ramlLocation;

  private final List<ResourceGenerator> resources = new ArrayList<>();
  private final Map<String, TypeGenerator> builtTypes = new ConcurrentHashMap<>();

  private GlobalResourceExtension.Composite resourceExtensionList = new GlobalResourceExtension.Composite();

  private Map<String, GeneratorType> foundTypes = new HashMap<>();

  private final List<JavaPoetTypeGenerator> supportGenerators = new ArrayList<>();
  private Configuration configuration;
  private Set<JavaPoetTypeGenerator> implementations = new HashSet<>();

  private ArrayListMultimap<JavaPoetTypeGenerator, JavaPoetTypeGenerator> internalTypesPerClass = ArrayListMultimap.create();

  private File schemaRepository;

  public CurrentBuild(GFinder typeFinder, Api api, ExtensionManager extensionManager, File ramlLocation) {

    this.typeFinder = typeFinder;
    this.api = api;
    this.extensionManager = extensionManager;
    this.ramlLocation = ramlLocation;
    this.configuration = Configuration.defaultConfiguration();
  }


  public File getSchemaRepository() {

    if (schemaRepository == null) {

      schemaRepository = Files.createTempDir();
    }

    return schemaRepository;
  }

  public Api getApi() {
    return api;
  }

  public String getResourcePackage() {

    return configuration.getResourcePackage();
  }

  public String getModelPackage() {

    return configuration.getModelPackage();
  }

  public String getSupportPackage() {
    return configuration.getSupportPackage();
  }

  public void generate(final File rootDirectory) throws IOException {

    try {
      if (resources.size() > 0) {
        ResponseSupport.buildSupportClasses(rootDirectory, getSupportPackage());
      }

      for (TypeGenerator typeGenerator : builtTypes.values()) {

        if (typeGenerator instanceof RamlToPojoTypeGenerator) {

          RamlToPojoTypeGenerator ramlToPojoTypeGenerator = (RamlToPojoTypeGenerator) typeGenerator;
          ramlToPojoTypeGenerator.output(new CodeContainer<ResultingPojos>() {

            @Override
            public void into(ResultingPojos g) throws IOException {
              g.createFoundTypes(rootDirectory.getAbsolutePath());
            }
          });
        }
        if (typeGenerator instanceof JavaPoetTypeGenerator) {

          buildTypeTree(rootDirectory, (JavaPoetTypeGenerator) typeGenerator);
          continue;
        }

        if (typeGenerator instanceof CodeModelTypeGenerator) {
          CodeModelTypeGenerator b = (CodeModelTypeGenerator) typeGenerator;
          b.output(new CodeContainer<JCodeModel>() {

            @Override
            public void into(JCodeModel g) throws IOException {

              g.build(rootDirectory);
            }
          });
        }

      }


      for (ResourceGenerator resource : resources) {
        resource.output(new CodeContainer<TypeSpec>() {

          @Override
          public void into(TypeSpec g) throws IOException {
            JavaFile.Builder file = JavaFile.builder(getResourcePackage(), g);
            file.build().writeTo(rootDirectory);
          }
        });
      }

      for (JavaPoetTypeGenerator typeGenerator : supportGenerators) {

        typeGenerator.output(new CodeContainer<TypeSpec.Builder>() {

          @Override
          public void into(TypeSpec.Builder g) throws IOException {

            JavaFile.Builder file = JavaFile.builder(getSupportPackage(), g.build());
            file.build().writeTo(rootDirectory);
          }
        });
      }

    } finally {

      if (schemaRepository != null) {

        FileUtils.deleteDirectory(schemaRepository);
      }
    }
  }

  private void buildTypeTree(final File rootDirectory, final JavaPoetTypeGenerator typeGenerator) throws IOException {
    JavaPoetTypeGenerator b = typeGenerator;

    b.output(new CodeContainer<TypeSpec.Builder>() {

      @Override
      public void into(final TypeSpec.Builder containing) throws IOException {

        for (final JavaPoetTypeGenerator generator : internalTypesPerClass.get(typeGenerator)) {

          generator.output(new CodeContainer<TypeSpec.Builder>() {

            @Override
            public void into(TypeSpec.Builder g) throws IOException {
              g.addModifiers(Modifier.PUBLIC, Modifier.STATIC);
              containing.addType(g.build());
            }
          }, BuildPhase.INTERFACE);
        }

        if (containing != null) {

          JavaFile.Builder file = JavaFile.builder(getModelPackage(), containing.build());
          file.build().writeTo(rootDirectory);
        }
      }
    },
             BuildPhase.INTERFACE
        );

    if (implementations.contains(b)) {

      b.output(new CodeContainer<TypeSpec.Builder>() {

        @Override
        public void into(final TypeSpec.Builder containing) throws IOException {

          for (final JavaPoetTypeGenerator generator : internalTypesPerClass.get(typeGenerator)) {

            generator.output(new CodeContainer<TypeSpec.Builder>() {

              @Override
              public void into(TypeSpec.Builder g) throws IOException {
                g.addModifiers(Modifier.PUBLIC, Modifier.STATIC);
                containing.addType(g.build());
              }
            }, BuildPhase.IMPLEMENTATION);
          }

          if (containing != null) {
            JavaFile.Builder file = JavaFile.builder(getModelPackage(), containing.build());
            file.build().writeTo(rootDirectory);
          }
        }
      },
               BuildPhase.IMPLEMENTATION);
    }
  }

  public void newGenerator(String ramlTypeName, TypeGenerator generator) {

    builtTypes.put(ramlTypeName, generator);
  }

  public File getRamlLocation() {
    return ramlLocation;
  }

  public void newSupportGenerator(JavaPoetTypeGenerator generator) {

    supportGenerators.add(generator);
  }

  public <T extends TypeGenerator> T getBuiltType(String ramlType) {

    TypeGenerator type = builtTypes.get(ramlType);
    if (type == null) {

      throw new GenerationException("no such type " + ramlType);
    }

    return (T) type;
  }

  public void newResource(ResourceGenerator rg) {

    resources.add(rg);
  }

  public void constructClasses() {

    TypeFindingListener listener = new TypeFindingListener(foundTypes);
    typeFinder.findTypes(listener);

    typeFinder.setupConstruction(this);
    for (GeneratorType type : foundTypes.values()) {

      type.construct(this);
    }
  }


  public List<String> typeConfiguration() {

    return Arrays.asList(this.configuration.getTypeConfiguration());
  }

  public void setConfiguration(Configuration configuration) {
    this.configuration = configuration;
  }

  public GenerationConfig getJsonMapperConfig() {
    return configuration.createJsonSchemaGenerationConfig();
  }

  private <T> T buildGlobalForCreate(T defaultValue) {

    if (configuration.getDefaultCreationExtension() != null) {

      try {
        return (T) configuration.getDefaultCreationExtension().newInstance();
      } catch (InstantiationException | IllegalAccessException e) {
        throw new GenerationException(e);
      }
    } else {
      return defaultValue;
    }
  }

  private GlobalResourceExtension buildGlobalForFinish() {

    if (configuration.getDefaultCreationExtension() != null) {

      try {
        return configuration.getDefaultFinishExtension().newInstance();
      } catch (InstantiationException | IllegalAccessException e) {
        throw new GenerationException(e);
      }
    } else {
      return GlobalResourceExtension.NULL_EXTENSION;
    }
  }


  public ResourceMethodExtension<GMethod> getResourceMethodExtension(
                                                                     Annotations<ResourceMethodExtension<GMethod>> onResourceMethodExtension,
                                                                     GMethod gMethod) {

    if (gMethod instanceof V10GMethod) {
      return onResourceMethodExtension.getWithContext(this, getApi(), ((V10GMethod) gMethod).implementation());
    }

    return onResourceMethodExtension == Annotations.ON_METHOD_CREATION ? buildGlobalForCreate(GlobalResourceExtension.NULL_EXTENSION)
        : buildGlobalForFinish();
  }

  public ResourceClassExtension<GResource> getResourceClassExtension(ResourceClassExtension<GResource> defaultClass,
                                                                     Annotations<ResourceClassExtension<GResource>> onResourceClassCreation,
                                                                     GResource topResource) {
    if (topResource instanceof V10GResource) {
      List<ResourceClassExtension<GResource>> list = new ArrayList<>();
      list.add(defaultClass);
      list.add(onResourceClassCreation.getWithContext(this, getApi(), ((V10GResource) topResource).implementation()));

      return new ResourceClassExtension.Composite(list);
    }

    return onResourceClassCreation == Annotations.ON_RESOURCE_CLASS_CREATION ? buildGlobalForCreate(defaultClass)
        : buildGlobalForFinish();
  }

  public ResponseClassExtension<GMethod> getResponseClassExtension(
                                                                   Annotations<ResponseClassExtension<GMethod>> onResponseClassCreation,
                                                                   GMethod gMethod) {
    if (gMethod instanceof V10GMethod) {
      return onResponseClassCreation.getWithContext(this, getApi(), ((V10GMethod) gMethod).implementation());
    }

    return onResponseClassCreation == Annotations.ON_RESPONSE_CLASS_CREATION ? buildGlobalForCreate(GlobalResourceExtension.NULL_EXTENSION)
        : buildGlobalForFinish();
  }

  public ResponseMethodExtension<GResponse> getResponseMethodExtension(
                                                                       Annotations<ResponseMethodExtension<GResponse>> onResponseMethodExtension,
                                                                       GResponse gResponse) {
    if (gResponse instanceof V10GResponse) {
      return onResponseMethodExtension.getWithContext(this, getApi(), ((V10GResponse) gResponse).implementation());
    }

    return onResponseMethodExtension == Annotations.ON_RESPONSE_METHOD_CREATION ? buildGlobalForCreate(GlobalResourceExtension.NULL_EXTENSION)
        : buildGlobalForFinish();
  }


  public void internalClass(JavaPoetTypeGenerator simpleTypeGenerator, JavaPoetTypeGenerator internalGenerator) {

    internalTypesPerClass.put(simpleTypeGenerator, internalGenerator);
  }

  public <T> Iterable<T> createExtensions(String className) {

    try {
      Class c = Class.forName(className);
      return (Iterable<T>) singletonList(c.newInstance());
    } catch (ClassNotFoundException e) {


      return FluentIterable.from(extensionManager.getClassesForName(className)).transform(new Function<Class, T>() {

        @Nullable
        @Override
        public T apply(@Nullable Class input) {
          try {
            return (T) input.newInstance();
          } catch (InstantiationException | IllegalAccessException e1) {

            throw new GenerationException(e1);
          }
        }
      });
    } catch (IllegalAccessException | InstantiationException e) {
      throw new GenerationException(e);
    }
  }

  public GlobalResourceExtension withResourceListeners() {

    return resourceExtensionList;
  }
}
