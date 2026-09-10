/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ketraterm.completion.model

/**
 * Curated container and orchestration specifications (Docker, Docker Compose, Kubectl).
 */
internal object ContainerCommandSpecs {
    fun docker(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "docker",
            description = "container platform CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec("ps", "list containers"),
                    TerminalCommandSpec("run", "run a command in a new container"),
                    TerminalCommandSpec("exec", "run a command in a running container"),
                    TerminalCommandSpec("build", "build an image from a Dockerfile"),
                    TerminalCommandSpec("images", "list images"),
                    TerminalCommandSpec("pull", "download an image from a registry"),
                    TerminalCommandSpec("push", "upload an image to a registry"),
                    TerminalCommandSpec("stop", "stop one or more running containers"),
                    TerminalCommandSpec("start", "start one or more stopped containers"),
                    TerminalCommandSpec("restart", "restart one or more containers"),
                    TerminalCommandSpec("rm", "remove one or more containers"),
                    TerminalCommandSpec("rmi", "remove one or more images"),
                    TerminalCommandSpec("logs", "fetch the logs of a container"),
                    TerminalCommandSpec("inspect", "return low-level information on Docker objects"),
                    TerminalCommandSpec("network", "manage networks"),
                    TerminalCommandSpec("volume", "manage volumes"),
                    TerminalCommandSpec("system", "manage Docker"),
                    TerminalCommandSpec(
                        name = "compose",
                        description = "manage Compose applications",
                        subcommands =
                            listOf(
                                TerminalCommandSpec("up", "create and start containers"),
                                TerminalCommandSpec("down", "stop and remove containers"),
                                TerminalCommandSpec("ps", "list containers"),
                                TerminalCommandSpec("logs", "view output from containers"),
                                TerminalCommandSpec("build", "build or rebuild services"),
                                TerminalCommandSpec("exec", "execute a command in a running container"),
                                TerminalCommandSpec("run", "run a one-off command"),
                                TerminalCommandSpec("restart", "restart service containers"),
                                TerminalCommandSpec("stop", "stop services"),
                                TerminalCommandSpec("start", "start services"),
                            ),
                    ),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(
                        names = listOf("--context"),
                        description = "select Docker context",
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.DOCKER_CONTEXT,
                    ),
                ),
        )

    fun dockerCompose(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "docker-compose",
            description = "define and run multi-container Docker applications",
            subcommands =
                listOf(
                    TerminalCommandSpec("up", "build, (re)create, start, and attach to containers for a service"),
                    TerminalCommandSpec("down", "stop and remove containers, networks, images, and volumes"),
                    TerminalCommandSpec("ps", "list containers"),
                    TerminalCommandSpec("logs", "view output from containers"),
                    TerminalCommandSpec("build", "build or rebuild services"),
                    TerminalCommandSpec("exec", "execute a command in a running container"),
                    TerminalCommandSpec("run", "run a one-off command on a service"),
                    TerminalCommandSpec("restart", "restart service containers"),
                    TerminalCommandSpec("stop", "stop running containers without removing them"),
                    TerminalCommandSpec("start", "start existing containers for a service"),
                    TerminalCommandSpec("config", "validate and view the Compose file"),
                    TerminalCommandSpec("pull", "pull service images"),
                    TerminalCommandSpec("push", "push service images"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help", "-h"), "show help"),
                    TerminalOptionSpec(listOf("--version", "-v"), "show version"),
                    TerminalOptionSpec(
                        listOf("-f", "--file"),
                        "specify an alternate compose file",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(listOf("-p", "--project-name"), "specify an alternate project name", requiresValue = true),
                    TerminalOptionSpec(listOf("-d", "--detach"), "detached mode: Run containers in the background"),
                    TerminalOptionSpec(listOf("--build"), "build images before starting containers"),
                    TerminalOptionSpec(listOf("--remove-orphans"), "remove containers for services not defined in the Compose file"),
                ),
        )

    fun kubectl(): TerminalCommandSpec =
        TerminalCommandSpec(
            name = "kubectl",
            description = "Kubernetes cluster CLI",
            subcommands =
                listOf(
                    TerminalCommandSpec(
                        name = "get",
                        description = "display one or many resources",
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    name = "resource",
                                    description = "resource type",
                                    valueCandidates = KUBECTL_RESOURCES,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    names = listOf("-o", "--output"),
                                    description = "output format",
                                    requiresValue = true,
                                    valueCandidates = listOf("yaml", "json", "wide", "name"),
                                ),
                                TerminalOptionSpec(
                                    listOf("-A", "--all-namespaces"),
                                    "if present, list the requested object(s) across all namespaces",
                                ),
                                TerminalOptionSpec(listOf("-l", "--selector"), "selector (label query) to filter on", requiresValue = true),
                                TerminalOptionSpec(
                                    listOf("-w", "--watch"),
                                    "after listing/getting the requested object, watch for changes",
                                ),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "describe",
                        description = "show details of a specific resource or group of resources",
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    name = "resource",
                                    description = "resource type",
                                    valueCandidates = KUBECTL_RESOURCES,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-A", "--all-namespaces"),
                                    "if present, describe the requested object(s) across all namespaces",
                                ),
                                TerminalOptionSpec(listOf("-l", "--selector"), "selector (label query) to filter on", requiresValue = true),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "logs",
                        description = "print the logs for a container in a pod",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-f", "--follow"), "specify if the logs should be streamed"),
                                TerminalOptionSpec(
                                    listOf("-p", "--previous"),
                                    "if true, print the logs for the previous instance of the container in a pod",
                                ),
                                TerminalOptionSpec(listOf("-c", "--container"), "print the logs of this container", requiresValue = true),
                                TerminalOptionSpec(listOf("--tail"), "lines of recent log file to display", requiresValue = true),
                                TerminalOptionSpec(listOf("--timestamps"), "include timestamps on each line in the log output"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "exec",
                        description = "execute a command in a container",
                        options =
                            listOf(
                                TerminalOptionSpec(listOf("-i", "--stdin"), "pass stdin to the container"),
                                TerminalOptionSpec(listOf("-t", "--tty"), "stdin is a TTY"),
                                TerminalOptionSpec(listOf("-c", "--container"), "container name", requiresValue = true),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "apply",
                        description = "apply a configuration to a resource by file name or stdin",
                        positionalArgumentPathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-f", "--filename"),
                                    "the files that contain the configurations to apply",
                                    requiresValue = true,
                                    valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                                TerminalOptionSpec(listOf("-R", "--recursive"), "process the directory used in -f, --filename recursively"),
                            ),
                    ),
                    TerminalCommandSpec(
                        name = "delete",
                        description = "delete resources by file names, stdin, resources and names",
                        positionalArguments =
                            listOf(
                                TerminalArgumentSpec(
                                    name = "resource",
                                    description = "resource type",
                                    valueCandidates = KUBECTL_RESOURCES,
                                ),
                            ),
                        options =
                            listOf(
                                TerminalOptionSpec(
                                    listOf("-f", "--filename"),
                                    "containing the resource to delete",
                                    requiresValue = true,
                                    valuePathKind = TerminalPathArgumentKind.FILE_OR_DIRECTORY,
                                ),
                                TerminalOptionSpec(listOf("-l", "--selector"), "selector (label query) to filter on", requiresValue = true),
                                TerminalOptionSpec(
                                    listOf("--force"),
                                    "if true, immediately remove resources from API and bypass graceful deletion",
                                ),
                            ),
                    ),
                    TerminalCommandSpec("port-forward", "forward one or more local ports to a pod"),
                    TerminalCommandSpec("config", "modify kubeconfig files"),
                    TerminalCommandSpec("create", "create a resource from a file or from stdin"),
                    TerminalCommandSpec("edit", "edit a resource on the server"),
                    TerminalCommandSpec("top", "display resource (CPU/memory) usage"),
                    TerminalCommandSpec("rollout", "manage the rollout of a resource"),
                    TerminalCommandSpec("scale", "set a new size for a deployment, replicaSet, or replicationController"),
                    TerminalCommandSpec("drain", "drain node in preparation for maintenance"),
                    TerminalCommandSpec("cordon", "mark node as unschedulable"),
                    TerminalCommandSpec("uncordon", "mark node as schedulable"),
                    TerminalCommandSpec("run", "run a particular image on the cluster"),
                    TerminalCommandSpec("explain", "get documentation for a resource"),
                ),
            options =
                listOf(
                    TerminalOptionSpec(listOf("--help"), "show help"),
                    TerminalOptionSpec(
                        names = listOf("--kubeconfig"),
                        description = "path to the kubeconfig file",
                        requiresValue = true,
                        valuePathKind = TerminalPathArgumentKind.FILE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--namespace", "-n"),
                        description = "kubernetes namespace to use",
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.KUBERNETES_NAMESPACE,
                    ),
                    TerminalOptionSpec(
                        names = listOf("--context"),
                        description = "name of the kubeconfig context to use",
                        requiresValue = true,
                        valueDomain = TerminalCompletionValueDomain.KUBERNETES_CONTEXT,
                    ),
                ),
        )

    internal val KUBECTL_RESOURCES =
        listOf(
            "pods",
            "services",
            "deployments",
            "configmaps",
            "secrets",
            "namespaces",
            "nodes",
            "ingress",
            "statefulsets",
            "persistentvolumeclaims",
            "events",
            "cronjobs",
        )
}

internal val KUBECTL_RESOURCES: List<String>
    get() = ContainerCommandSpecs.KUBECTL_RESOURCES
