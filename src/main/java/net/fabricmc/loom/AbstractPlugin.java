/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom;

import com.google.gson.Gson;
import net.fabricmc.loom.task.DownloadTask;
import net.fabricmc.loom.util.Constants;
import com.google.common.collect.ImmutableMap;
import net.fabricmc.loom.util.Version;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.repositories.FlatDirectoryArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.idea.model.IdeaModel;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class AbstractPlugin implements Plugin<Project> {
    protected Project project;

    @Override
    public void apply(Project target) {
        this.project = target;

        // Apply default plugins
        project.apply(ImmutableMap.of("plugin", "java"));
        project.apply(ImmutableMap.of("plugin", "eclipse"));
        project.apply(ImmutableMap.of("plugin", "idea"));

        project.getExtensions().create("minecraft", LoomGradleExtension.class);

        // Force add Mojang repository
        addMavenRepo(target, "Mojang", "https://libraries.minecraft.net/");

        // Minecraft libraries configuration
        project.getConfigurations().maybeCreate(Constants.CONFIG_MC_DEPENDENCIES);
        project.getConfigurations().maybeCreate(Constants.CONFIG_MC_DEPENDENCIES_CLIENT);
        project.getConfigurations().maybeCreate(Constants.CONFIG_NATIVES);

        // Common libraries extends from client libraries, CONFIG_MC_DEPENDENCIES will contains all MC dependencies
        project.getConfigurations().getByName(Constants.CONFIG_MC_DEPENDENCIES).extendsFrom(project.getConfigurations().getByName(Constants.CONFIG_MC_DEPENDENCIES_CLIENT));

        configureIDEs();
        configureCompile();
    }

    /**
     * Permit to create a Task instance of the type in the project
     *
     * @param name The name of the task
     * @param type The type of the task that will be used to create an instance
     * @return The created task object for the project
     */
    public <T extends Task> T makeTask(String name, Class<T> type) {
        return makeTask(project, name, type);
    }

    /**
     * Permit to create a Task instance of the type in a project
     *
     * @param target The target project
     * @param name   The name of the task
     * @param type   The type of the task that will be used to create an instance
     * @return The created task object for the specified project
     */
    public static <T extends Task> T makeTask(Project target, String name, Class<T> type) {
        return target.getTasks().create(name, type);
    }

    /**
     * Permit to add a Maven repository to a target project
     *
     * @param target The garget project
     * @param name   The name of the repository
     * @param url    The URL of the repository
     * @return An object containing the name and the URL of the repository that can be modified later
     */
    public MavenArtifactRepository addMavenRepo(Project target, final String name, final String url) {
        return target.getRepositories().maven(repo -> {
            repo.setName(name);
            repo.setUrl(url);
        });
    }

    /**
     * Add Minecraft dependencies to IDE dependencies
     */
    protected void configureIDEs() {
        // IDEA
        IdeaModel ideaModule = (IdeaModel) project.getExtensions().getByName("idea");

        ideaModule.getModule().getExcludeDirs().addAll(project.files(".gradle", "build", ".idea", "out").getFiles());
        ideaModule.getModule().setDownloadJavadoc(true);
        ideaModule.getModule().setDownloadSources(true);
        ideaModule.getModule().setInheritOutputDirs(true);
        ideaModule.getModule().getScopes().get("COMPILE").get("plus").add(project.getConfigurations().getByName(Constants.CONFIG_MC_DEPENDENCIES));

        // ECLIPSE
        EclipseModel eclipseModule = (EclipseModel) project.getExtensions().getByName("eclipse");
        eclipseModule.getClasspath().getPlusConfigurations().add(project.getConfigurations().getByName(Constants.CONFIG_MC_DEPENDENCIES));
    }

    /**
     * Add Minecraft dependencies to compile time
     */
    protected void configureCompile() {
        JavaPluginConvention javaModule = (JavaPluginConvention) project.getConvention().getPlugins().get("java");

        SourceSet main = javaModule.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        SourceSet test = javaModule.getSourceSets().getByName(SourceSet.TEST_SOURCE_SET_NAME);

        main.setCompileClasspath(main.getCompileClasspath().plus(project.getConfigurations().getByName(Constants.CONFIG_MC_DEPENDENCIES)));
        test.setCompileClasspath(test.getCompileClasspath().plus(project.getConfigurations().getByName(Constants.CONFIG_MC_DEPENDENCIES)));
        main.setRuntimeClasspath(main.getCompileClasspath().plus(project.getConfigurations().getByName(Constants.CONFIG_MC_DEPENDENCIES)));
        test.setCompileClasspath(test.getCompileClasspath().plus(project.getConfigurations().getByName(Constants.CONFIG_MC_DEPENDENCIES)));

        Javadoc javadoc = (Javadoc) project.getTasks().getByName(JavaPlugin.JAVADOC_TASK_NAME);
        javadoc.setClasspath(main.getOutput().plus(main.getCompileClasspath()));

        project.afterEvaluate(project1 -> {
            LoomGradleExtension extension = project1.getExtensions().getByType(LoomGradleExtension.class);
            project1.getRepositories().flatDir(flatDirectoryArtifactRepository -> {
                flatDirectoryArtifactRepository.dir(Constants.CACHE_FILES);
                flatDirectoryArtifactRepository.setName("LoomCacheFiles");
            });

            project1.getRepositories().maven(mavenArtifactRepository -> {
                mavenArtifactRepository.setName("FabricMC");
                mavenArtifactRepository.setUrl("http://maven.fabricmc.net/");
            });

            project1.getRepositories().maven(mavenArtifactRepository -> {
                mavenArtifactRepository.setName("SpongePowered");
                mavenArtifactRepository.setUrl("http://repo.spongepowered.org/maven");
            });

            project1.getRepositories().maven(mavenArtifactRepository -> {
                mavenArtifactRepository.setName("Mojang");
                mavenArtifactRepository.setUrl("https://libraries.minecraft.net/");
            });

            project1.getRepositories().mavenCentral();
            project1.getRepositories().jcenter();

            Gson gson = new Gson();
            try {
                DownloadTask.downloadMcJson(extension, project1.getLogger());
                Version version = gson.fromJson(new FileReader(Constants.MINECRAFT_JSON.get(extension)), Version.class);
                for (Version.Library library : version.libraries) {
                    if (library.allowed() && library.getFile() != null) {
                        String configName = Constants.CONFIG_MC_DEPENDENCIES;
                        if (library.name.contains("java3d") || library.name.contains("paulscode") || library.name.contains("lwjgl") || library.name.contains("twitch") || library.name.contains("jinput")) {
                            configName = Constants.CONFIG_MC_DEPENDENCIES_CLIENT;
                        }
                        project1.getDependencies().add(configName, library.getArtifactName());
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            project1.getDependencies().add(Constants.CONFIG_MC_DEPENDENCIES, "net.minecraft:" +  Constants.MINECRAFT_CLIENT_MAPPED_JAR.get(extension).getName().replace(".jar", ""));

            if(extension.fabricVersion != null && !extension.fabricVersion.isEmpty()){
                //only add this when not in a fabric dev env
                project1.getDependencies().add(Constants.CONFIG_MC_DEPENDENCIES, "net.fabricmc:fabric-base:" + extension.version + "-" + extension.fabricVersion);
            }
        });


    }
}