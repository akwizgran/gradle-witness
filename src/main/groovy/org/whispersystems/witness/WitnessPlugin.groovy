package org.whispersystems.witness

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedDependencyResult

import java.security.MessageDigest

class WitnessPluginExtension {
    List verify
}

class WitnessPlugin implements Plugin<Project> {

    static final boolean VERBOSE = false

    static String calculateSha256(File file) {
        MessageDigest md = MessageDigest.getInstance("SHA-256")
        file.eachByte 4096, { bytes, size ->
            md.update(bytes, 0, size)
        }
        return md.digest().collect { String.format "%02x", it }.join()
    }

    /**
     * Converts a file path into a DependencyKey, assuming the path ends with the elements
     * "group/name/version/sha1/file".
     * See https://docs.gradle.org/current/userguide/dependency_cache.html
     */
    static DependencyKey makeKey(String path) {
        def parts = path.tokenize(System.getProperty('file.separator'))
        if (parts.size() < 5) throw new AssertionError()
        parts = parts.subList(parts.size() - 5, parts.size())
        return new DependencyKey(parts[0], parts[1], parts[2], parts[4])
    }

    /**
     * Finds any dependencies of the given configuration that are modules and
     * returns a set of files belonging to those dependencies.
     */
    static Set<File> findModuleDependencies(Configuration cfg) {
        def modules = new HashSet<>()
        cfg.incoming.resolutionResult.allDependencies.each {
            if (it instanceof ResolvedDependencyResult) {
                if (it.requested instanceof ModuleComponentSelector) {
                    def id = it.selected.id
                    if (id instanceof ModuleComponentIdentifier) {
                        modules.add "${id.group}:${id.module}:${id.version}".toString()
                    }
                }
            } else if (VERBOSE) {
                System.err.println "Skipping unresolved dependency ${it.requested.displayName}"
            }
        }
        def files = new HashSet<>()
        try {
            cfg.incoming.getArtifacts().getArtifacts().each {
                def id = it.variant.owner
                if (id instanceof ModuleComponentIdentifier) {
                    def key = "${id.group}:${id.module}:${id.version}".toString()
                    if (modules.contains(key)) {
                        files.add(it.file)
                    }
                }
            }
        } catch (Exception e) {
            System.err.println e.message
        }
        return files
    }

    static Map<DependencyKey, String> calculateHashes(Project project) {
        def excludedProp = project.property('noWitness')
        def excluded = excludedProp == null ? [] : excludedProp.split(',')
        def dependencies = new TreeMap<>()
        def addDependencies = {
            // Skip excluded configurations and their subconfigurations
            def scopedName = "${project.name}:${it.name}"
            if (it.hierarchy.any {
                def superScopedName = "${project.name}:${it.name}"
                excluded.contains(it.name) || excluded.contains(superScopedName)
            }) {
                if (VERBOSE) {
                    System.err.println "Skipping excluded configuration $scopedName"
                }
                return
            }
            // Skip unresolvable configurations
            if (!it.metaClass.respondsTo(it, 'isCanBeResolved') || it.canBeResolved) {
                findModuleDependencies(it).each {
                    def key = makeKey it.path
                    if (!dependencies.containsKey(key))
                        dependencies.put key, calculateSha256(it)
                }
            } else if (VERBOSE) {
                System.err.println "Skipping unresolved configuration $scopedName"
            }
        }
        project.configurations.each addDependencies
        project.buildscript.configurations.each addDependencies
        return dependencies
    }

    void apply(Project project) {
        project.extensions.create("dependencyVerification", WitnessPluginExtension)
        project.afterEvaluate {
            def dependencies = calculateHashes project
            project.dependencyVerification.verify.each { assertion ->
                def parts = assertion.tokenize(":")
                if (parts.size() != 5) {
                    throw new InvalidUserDataException("Invalid or obsolete integrity assertion '$assertion'")
                }
                def (group, name, version, file, expectedHash) = parts
                def key = new DependencyKey(group, name, version, file)
                System.err.println "Verifying ${key.all}"
                def hash = dependencies.get key
                if (hash == null) {
                    throw new InvalidUserDataException("No dependency for integrity assertion '$assertion'")
                }
                if (hash != expectedHash) {
                    throw new InvalidUserDataException("Checksum failed for ${key.all}")
                }
            }
        }

        project.tasks.register('calculateChecksums').get().doLast {
            def dependencies = calculateHashes project
            println "dependencyVerification {"
            println "    verify = ["
            dependencies.each { dep -> println "        '${dep.key.all}:${dep.value}'," }
            println "    ]"
            println "}"
        }
    }

    static class DependencyKey implements Comparable<DependencyKey> {

        final String group, name, version, file, all

        DependencyKey(group, name, version, file) {
            this.group = group
            this.name = name
            this.version = version
            this.file = file
            all = "$group:$name:$version:$file".toString()
        }

        @Override
        boolean equals(Object o) {
            if (o instanceof DependencyKey) return ((DependencyKey) o).all == all
            return false
        }

        @Override
        int hashCode() {
            return all.hashCode()
        }

        @Override
        int compareTo(DependencyKey k) {
            return all <=> k.all
        }

        @Override
        String toString() {
            return "$group:$name:$version".toString()
        }
    }
}
