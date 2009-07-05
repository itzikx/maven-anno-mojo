package org.jfrog.maven.annomojo.extractor;

import com.sun.tools.apt.Main;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.descriptor.InvalidPluginDescriptorException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.tools.plugin.PluginToolsRequest;
import org.apache.maven.tools.plugin.extractor.ExtractionException;
import org.apache.maven.tools.plugin.extractor.MojoDescriptorExtractor;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.codehaus.plexus.util.FileUtils;

import java.io.File;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java 1.5+ Annotation-based MojoDescriptorExtractor
 *
 * @author Yoav Landman (ylandman at gmail.com)
 * @author Frederic Simon (frederic.simon at gmail.com)
 */
public class AnnoMojoDescriptorExtractor
        extends AbstractLogEnabled implements MojoDescriptorExtractor, Contextualizable {

    protected PlexusContainer container;

    private MavenHelper helper;

    public void contextualize(Context context) throws ContextException {
        // System out is swallowed in here becareful
        // This method is called and always 2.0 even in 2.1 environment
        container = (PlexusContainer) context.get(PlexusConstants.PLEXUS_KEY);
        try {
            helper = new Maven20Helper(container);
        } catch (ComponentLookupException e) {
            throw new ContextException("Failed to configure the extractor Maven helper.", e);
        }
    }


    public List<MojoDescriptor> execute(PluginToolsRequest pluginToolsRequest)
            throws ExtractionException, InvalidPluginDescriptorException {
        List<MojoDescriptor> list = execute(pluginToolsRequest.getProject(), pluginToolsRequest.getPluginDescriptor());
        return list;
    }


    @SuppressWarnings({"unchecked"})
    public List<MojoDescriptor> execute(MavenProject project, PluginDescriptor pluginDescriptor)
            throws InvalidPluginDescriptorException {
        List<String> sourceRoots = project.getCompileSourceRoots();
        Set<String> sourcePathElements = new HashSet<String>();
        String srcRoot = null;
        try {
            for (String sourceRoot : sourceRoots) {
                srcRoot = sourceRoot;
                List<File> files = FileUtils.getFiles(new File(srcRoot), "**/*.java", null, true);
                for (File file : files) {
                    String path = file.getPath();
                    sourcePathElements.add(path);
                }
            }
        } catch (Exception e) {
            throw new InvalidPluginDescriptorException(
                    "Failed to get source files from " + srcRoot, e);
        }
        List<String> argsList = new ArrayList<String>();
        argsList.add("-nocompile");
        argsList.add("-cp");
        StringBuilder cp = new StringBuilder();
        //Add the compile classpath
        List<String> compileClasspathElements;
        try {
            compileClasspathElements = project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new InvalidPluginDescriptorException(
                    "Failed to get compileClasspathElements.", e);
        }
        for (String ccpe : compileClasspathElements) {
            appendToPath(cp, ccpe);
        }
        //Resolve dependencies and add them to the classpath
        resolveDependencies(project, cp);
        //Add the current CL classptah
        URL[] urls = ((URLClassLoader) getClass().getClassLoader()).getURLs();
        for (URL url : urls) {
            String path;
            try {
                path = url.getPath();
            } catch (Exception e) {
                throw new InvalidPluginDescriptorException(
                        "Failed to get classpath files from " + url, e);
            }
            appendToPath(cp, path);
        }
        String classpath = cp.toString();
        debug("cl=" + classpath);
        argsList.add(classpath);
        argsList.addAll(sourcePathElements);
        String[] args = argsList.toArray(new String[argsList.size()]);
        ArrayList<MojoDescriptor> descriptors = new ArrayList<MojoDescriptor>();
        MojoDescriptorTls.setDescriptors(descriptors);
        try {
            Main.process(new MojoApf(pluginDescriptor), new PrintWriter(System.out), args);
        } catch (Throwable t) {
            //TODO: [by yl] This is never caught - apt swallows the exception.
            //Use the TLS to hold thrown exception
            throw new InvalidPluginDescriptorException(
                    "Failed to extract plugin descriptor.", t);
        }
        return MojoDescriptorTls.getDescriptors();
    }

    private void debug(String msg) {
        Logger log = getLogger();
        if (log != null) {
            log.debug(msg);
        } else {
            System.out.println(msg);
        }
    }

    private void appendToPath(StringBuilder cp, String path) {
        if (path != null && path.length() > 0) {
            cp.append(path);
            cp.append(File.pathSeparator);
        }
    }

    @SuppressWarnings({"unchecked"})
    private void resolveDependencies(MavenProject project, StringBuilder cp) throws InvalidPluginDescriptorException {
        if (container == null) {
            // Not executed from maven
            return;
        }
        ArtifactResolver resolver;
        try {
            resolver = (ArtifactResolver) container.lookup(ArtifactResolver.ROLE);
        } catch (ComponentLookupException e) {
            throw new InvalidPluginDescriptorException(
                    "Failed to get the ArtifactResolver.", e);
        }
        ArtifactRepository localRepository;
        try {
            localRepository = helper.getLocalRepository();
        } catch (Exception e) {
            throw new InvalidPluginDescriptorException(
                    "Failed to get the local repository.", e);
        }
        ArtifactFactory artifactFactory;
        try {
            artifactFactory = (ArtifactFactory) container.lookup(ArtifactFactory.ROLE);
        } catch (ComponentLookupException e) {
            throw new InvalidPluginDescriptorException(
                    "Failed to locate the artifact factory.", e);
        }
        ArtifactMetadataSource artifactMetadataSource;
        try {
            artifactMetadataSource = (ArtifactMetadataSource) container.lookup(ArtifactMetadataSource.ROLE, "maven");
        } catch (ComponentLookupException e) {
            throw new InvalidPluginDescriptorException(
                    "Failed to locate the Artifact Metadata Source.", e);
        }
        ArtifactFilter filter = new ArtifactFilter() {
            public boolean include(Artifact artifact) {
                String scope = artifact.getScope();
                return filterFromScope(scope);
            }
        };

        Set<Artifact> toResolve = new HashSet<Artifact>();
        List<Dependency> dependencies = project.getDependencies();
        for (Dependency dependency : dependencies) {
            String scope = dependency.getScope();
            if (!filterFromScope(scope)) {
                continue;
            }
            Artifact artifact = artifactFactory.createArtifact(
                    dependency.getGroupId(),
                    dependency.getArtifactId(),
                    dependency.getVersion(),
                    scope,
                    dependency.getType());
            toResolve.add(artifact);
        }
        Map managedVersions = createManagedVersionMap(project, artifactFactory);
        try {
            ArtifactResolutionResult result = resolver.resolveTransitively(
                    toResolve,
                    project.getArtifact(),
                    managedVersions,
                    localRepository,
                    project.getRemoteArtifactRepositories(),
                    artifactMetadataSource,
                    filter);
            Set<Artifact> artifacts = result.getArtifacts();
            for (Artifact artifact : artifacts) {
                File file = artifact.getFile();
                appendToPath(cp, file.getCanonicalPath());
            }
        } catch (Exception e) {
            throw new InvalidPluginDescriptorException(
                    "Failed to resolve transitively artifacts: " + e.getMessage(), e);
        }
    }

    private boolean filterFromScope(String scope) {
        // Only compile or provided should be used here. Scope null is compile
        if (scope != null &&
                (scope.equals(Artifact.SCOPE_SYSTEM) ||
                        scope.equals(Artifact.SCOPE_TEST) ||
                        scope.equals(Artifact.SCOPE_RUNTIME))) {
            return false;
        }
        return true;
    }

    private Map<String, Artifact> createManagedVersionMap(MavenProject project, ArtifactFactory artifactFactory)
            throws InvalidPluginDescriptorException {
        Map<String, Artifact> map;
        DependencyManagement dependencyManagement = project.getDependencyManagement();
        String projectId = project.getId();

        if (dependencyManagement != null && dependencyManagement.getDependencies() != null) {
            map = new HashMap<String, Artifact>();
            //noinspection unchecked
            for (Dependency d : (List<Dependency>) dependencyManagement.getDependencies()) {
                try {
                    VersionRange versionRange = VersionRange.createFromVersionSpec(d.getVersion());
                    Artifact artifact = artifactFactory.createDependencyArtifact(d.getGroupId(), d.getArtifactId(),
                            versionRange, d.getType(),
                            d.getClassifier(), d.getScope(),
                            d.isOptional());
                    map.put(d.getManagementKey(), artifact);
                }
                catch (InvalidVersionSpecificationException e) {
                    throw new InvalidPluginDescriptorException("Unable to parse version '" + d.getVersion() +
                            "' for dependency '" + d.getManagementKey() + "' in project " + projectId + " : " +
                            e.getMessage(), e);
                }
            }
        } else {
            map = Collections.emptyMap();
        }
        return map;
    }
}
