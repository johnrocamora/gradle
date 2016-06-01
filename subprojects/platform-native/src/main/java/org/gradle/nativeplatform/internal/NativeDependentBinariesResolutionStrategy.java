/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.nativeplatform.internal;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import org.gradle.api.Nullable;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.LibraryBinaryIdentifier;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectRegistry;
import org.gradle.api.internal.resolve.ProjectModelResolver;
import org.gradle.language.base.plugins.ComponentModelBasePlugin;
import org.gradle.model.ModelMap;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.model.internal.type.ModelTypes;
import org.gradle.nativeplatform.NativeComponentSpec;
import org.gradle.nativeplatform.NativeLibraryBinary;
import org.gradle.platform.base.VariantComponentSpec;
import org.gradle.platform.base.internal.dependents.AbstractDependentBinariesResolutionStrategy;
import org.gradle.platform.base.internal.BinarySpecInternal;
import org.gradle.platform.base.internal.dependents.DefaultDependentBinariesResolvedResult;
import org.gradle.platform.base.internal.dependents.DependentBinariesResolvedResult;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;

public class NativeDependentBinariesResolutionStrategy extends AbstractDependentBinariesResolutionStrategy {

    private final ProjectRegistry<ProjectInternal> projectRegistry;
    private final ProjectModelResolver projectModelResolver;

    public NativeDependentBinariesResolutionStrategy(ProjectRegistry<ProjectInternal> projectRegistry, ProjectModelResolver projectModelResolver) {
        super();
        checkNotNull(projectRegistry, "ProjectRegistry must not be null");
        checkNotNull(projectModelResolver, "ProjectModelResolver must not be null");
        this.projectRegistry = projectRegistry;
        this.projectModelResolver = projectModelResolver;
    }

    @Nullable
    @Override
    protected List<DependentBinariesResolvedResult> resolveDependents(BinarySpecInternal target, boolean includeTestSuites) {
        if (!(target instanceof NativeBinarySpecInternal)) {
            return null;
        }
        return buildResolvedResult((NativeBinarySpecInternal) target, buildState(includeTestSuites));
    }

    private static class State {
        Map<String, NativeBinarySpecInternal> binaries = Maps.newLinkedHashMap();
        Map<String, List<NativeBinarySpecInternal>> dependencies = Maps.newLinkedHashMap();
    }

    private State buildState(boolean includeTestSuites) {
        State state = new State();

        List<ProjectInternal> orderedProjects = Ordering.usingToString().sortedCopy(projectRegistry.getAllProjects());
        for (ProjectInternal project : orderedProjects) {
            if (project.getPlugins().hasPlugin(ComponentModelBasePlugin.class)) {
                ModelRegistry modelRegistry = projectModelResolver.resolveProjectModel(project.getPath());
                ModelMap<NativeComponentSpec> components = modelRegistry.realize("components", ModelTypes.modelMap(NativeComponentSpec.class));
                for (NativeBinarySpecInternal binary : allBinariesOf(components)) {
                    state.binaries.put(stateKeyOf(binary), binary);
                }
                // TODO:PM Can't import TestingModelBasePlugin nor NativeTestSuiteSpec here to includeTestSuites
            }
        }

        for (Map.Entry<String, NativeBinarySpecInternal> entry : state.binaries.entrySet()) {
            String key = entry.getKey();
            NativeBinarySpecInternal nativeBinary = entry.getValue();
            if (state.dependencies.get(key) == null) {
                state.dependencies.put(key, Lists.<NativeBinarySpecInternal>newArrayList());
            }
            for (NativeLibraryBinary libraryBinary : nativeBinary.getDependentBinaries()) {
                // DEBT Unfortunate cast! see LibraryBinaryLocator
                state.dependencies.get(key).add((NativeBinarySpecInternal) libraryBinary);
            }
        }

        return state;
    }

    private Iterable<NativeBinarySpecInternal> allBinariesOf(ModelMap<NativeComponentSpec> components) {
        List<NativeBinarySpecInternal> binaries = Lists.newArrayList();
        for (VariantComponentSpec nativeComponent : components.withType(VariantComponentSpec.class)) {
            for (NativeBinarySpecInternal nativeBinary : nativeComponent.getBinaries().withType(NativeBinarySpecInternal.class)) {
                binaries.add(nativeBinary);
            }
        }
        return binaries;
    }

    private String stateKeyOf(BinarySpecInternal binary) {
        LibraryBinaryIdentifier id = binary.getId();
        return stateKeyOf(id.getProjectPath(), id.getLibraryName(), id.getVariant());
    }

    private String stateKeyOf(String project, String component, String binary) {
        String key = "";
        if (emptyToNull(project) == null) {
            key += Project.PATH_SEPARATOR;
        } else {
            key += project;
        }
        key += Project.PATH_SEPARATOR + component + Project.PATH_SEPARATOR + binary;
        return key;
    }

    private List<DependentBinariesResolvedResult> buildResolvedResult(NativeBinarySpecInternal target, State state) {
        List<DependentBinariesResolvedResult> result = Lists.newArrayList();
        List<NativeBinarySpecInternal> dependents = getDependents(target, state);
        for (NativeBinarySpecInternal dependent : dependents) {
            List<DependentBinariesResolvedResult> children = buildResolvedResult(dependent, state);
            result.add(new DefaultDependentBinariesResolvedResult(dependent.getId(), dependent.isBuildable(), false, children));
        }
        return result;
    }

    private List<NativeBinarySpecInternal> getDependents(NativeBinarySpecInternal target, State state) {
        List<NativeBinarySpecInternal> dependents = Lists.newArrayList();
        for (Map.Entry<String, List<NativeBinarySpecInternal>> entry : state.dependencies.entrySet()) {
            if (entry.getValue().contains(target)) {
                dependents.add(state.binaries.get(entry.getKey()));
            }
        }
        return dependents;
    }
}