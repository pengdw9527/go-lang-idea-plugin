package com.goide.jps.builder;

import com.goide.jps.model.JpsGoModuleType;
import com.goide.jps.model.JpsGoSdkType;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.BaseOSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.util.CommonProcessors;
import com.intellij.util.Function;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.builders.BuildOutputConsumer;
import org.jetbrains.jps.builders.DirtyFilesHolder;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.ProjectBuildException;
import org.jetbrains.jps.incremental.TargetBuilder;
import org.jetbrains.jps.incremental.messages.BuildMessage;
import org.jetbrains.jps.incremental.messages.CompilerMessage;
import org.jetbrains.jps.incremental.resources.ResourcesBuilder;
import org.jetbrains.jps.incremental.resources.StandardResourceBuilderEnabler;
import org.jetbrains.jps.model.JpsDummyElement;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;
import org.jetbrains.jps.model.library.sdk.JpsSdk;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.module.JpsModuleSourceRoot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class GoBuilder extends TargetBuilder<GoSourceRootDescriptor, GoTarget> {
  public static final String NAME = "go";
  private final static Logger LOG = Logger.getInstance(GoBuilder.class);

  public GoBuilder() {
    super(Arrays.asList(GoTargetType.PRODUCTION, GoTargetType.TESTS));
    ResourcesBuilder.registerEnabler(new StandardResourceBuilderEnabler() {
      @Override
      public boolean isResourceProcessingEnabled(@NotNull JpsModule module) {
        return !(module.getModuleType() instanceof JpsGoModuleType);
      }
    });
  }

  @Override
  public void build(@NotNull GoTarget target,
                    @NotNull DirtyFilesHolder<GoSourceRootDescriptor, GoTarget> holder,
                    @NotNull BuildOutputConsumer outputConsumer,
                    @NotNull CompileContext context) throws ProjectBuildException, IOException {
    LOG.debug(target.getPresentableName());
    if (!holder.hasDirtyFiles() && !holder.hasRemovedFiles()) return;

    JpsModule module = target.getModule();
    JpsSdk<JpsDummyElement> sdk = getSdk(context, module);
    File executable = JpsGoSdkType.getGoExecutableFile(sdk.getHomePath());

    File outputDirectory = getBuildOutputDirectory(module, target.isTests(), context);

    for (String path : getGoModulePaths(module, target)) {
      GeneralCommandLine commandLine = new GeneralCommandLine();
      commandLine.setWorkDirectory(outputDirectory);
      commandLine.setExePath(executable.getAbsolutePath());
      commandLine.addParameter("build");
      String resultBinaryName = getBinaryFileNameForPath(path);
      commandLine.addParameters("-o", outputDirectory.getAbsolutePath() + File.separatorChar + resultBinaryName);
      commandLine.addParameters(path);
      runBuildProcess(context, commandLine, path);
    }
  }

  @NotNull
  @Override
  public String getPresentableName() {
    return NAME;
  }

  @NotNull
  private static File getBuildOutputDirectory(@NotNull JpsModule module,
                                              boolean forTests,
                                              @NotNull CompileContext context) throws ProjectBuildException {
    JpsJavaExtensionService instance = JpsJavaExtensionService.getInstance();
    File outputDirectory = instance.getOutputDirectory(module, forTests);
    if (outputDirectory == null) {
      String errorMessage = "No output dir for module " + module.getName();
      context.processMessage(new CompilerMessage(NAME, BuildMessage.Kind.ERROR, errorMessage));
      throw new ProjectBuildException(errorMessage);
    }
    if (!outputDirectory.exists()) {
      FileUtil.createDirectory(outputDirectory);
    }
    return outputDirectory;
  }

  @NotNull
  private static JpsSdk<JpsDummyElement> getSdk(@NotNull CompileContext context,
                                                @NotNull JpsModule module) throws ProjectBuildException {
    JpsSdk<JpsDummyElement> sdk = module.getSdk(JpsGoSdkType.INSTANCE);
    if (sdk == null) {
      String errorMessage = "No SDK for module " + module.getName();
      context.processMessage(new CompilerMessage(NAME, BuildMessage.Kind.ERROR, errorMessage));
      throw new ProjectBuildException(errorMessage);
    }
    return sdk;
  }

  private static void runBuildProcess(@NotNull CompileContext context, @NotNull GeneralCommandLine commandLine, String path)
    throws ProjectBuildException {
    Process process;
    try {
      process = commandLine.createProcess();
    }
    catch (ExecutionException e) {
      throw new ProjectBuildException("Failed to launch go compiler", e);
    }
    BaseOSProcessHandler handler = new BaseOSProcessHandler(process, commandLine.getCommandLineString(), Charset.defaultCharset());
    ProcessAdapter adapter = new GoCompilerProcessAdapter(context, NAME, path);
    handler.addProcessListener(adapter);
    handler.startNotify();
    handler.waitFor();
  }

  @NotNull
  private static List<String> getGoModulePaths(@NotNull JpsModule module, @NotNull final GoTarget target) {
    CommonProcessors.CollectProcessor<File> goFilesCollector = new CommonProcessors.CollectProcessor<File>() {
      @Override
      protected boolean accept(@NotNull File file) {
        return !file.isDirectory() && FileUtilRt.extensionEquals(file.getName(), "go") &&
               (target.isTests() || !FileUtil.getNameWithoutExtension(file).endsWith("_test"));
      }
    };
    List<JpsModuleSourceRoot> sourceRoots = new ArrayList<JpsModuleSourceRoot>();
    ContainerUtil.addAll(sourceRoots, module.getSourceRoots(JavaSourceRootType.SOURCE));
    ContainerUtil.addAll(sourceRoots, module.getSourceRoots(JavaSourceRootType.TEST_SOURCE));
    for (JpsModuleSourceRoot root : sourceRoots) {
      FileUtil.processFilesRecursively(root.getFile(), goFilesCollector);
    }
    return ContainerUtil.map(goFilesCollector.getResults(), new Function<File, String>() {
      @NotNull
      @Override
      public String fun(@NotNull File file) {
        return file.getAbsolutePath();
      }
    });
  }


  @NotNull
  private static String getBinaryFileNameForPath(@NotNull String path) {
    String resultBinaryName = FileUtil.getNameWithoutExtension(PathUtil.getFileName(path));
    return SystemInfo.isWindows ? resultBinaryName + ".exe" : resultBinaryName;
  }
}
