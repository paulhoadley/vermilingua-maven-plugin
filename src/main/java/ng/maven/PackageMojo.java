package ng.maven;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class PackageMojo extends AbstractMojo {

	/**
	 * The maven project. This gets injected by Maven during the build
	 */
	@Parameter(property = "project", required = true, readonly = true)
	MavenProject project;

	/**
	 * Allows the user to specify an alternative folder name for the resources folder
	 * I.e. "resources" (for compatibility with older behaviour).
	 *
	 * CHECKME: I'd prefer not to include this and just standardize on the new/correct bundle layout with a separate "woresources" folder
	 */
	@Parameter(property = "woresourcesFolderName", required = false, defaultValue = "woresources")
	String woresourcesFolderName;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {

		// This sill usually be maven's 'target' directory
		final Path buildPath = Paths.get( project.getBuild().getDirectory() );

		// This is the jar file resulting from the compilation of our application project (App.jar)
		final Path artifactPath = project.getArtifact().getFile().toPath();

		// This is the WOA bundle, the destination for our build. Bundle gets named after the app's artifactId
		final WOA woa = WOA.getAtPath( buildPath, project.getArtifactId() );

		// This will be the eventual name of the app's JAR file. Lowercase app name with .jar appended.
		final String appJarFilename = project.getArtifact().getArtifactId().toLowerCase() + ".jar";

		// Copy the app jar to the woa
		Util.copyFile( artifactPath, woa.javaPath().resolve( appJarFilename ) );

		// Start working on that list of jar paths for the classpath
		final List<String> classpathStrings = new ArrayList<>();

		// CHECKME: For some reason the older plugin includes the java folder itself on the classpath. Better replicate that
		classpathStrings.add( "Contents/Resources/Java/" );

		// CHECKME: Not a fan of using hardcoded folder names
		classpathStrings.add( "Contents/Resources/Java/" + appJarFilename );

		// Copy the app's resolved dependencies (direct and transient) to the WOA
		for( final Artifact artifact : (Set<Artifact>)project.getArtifacts() ) {
			final Path artifactPathInMavenRepository = artifact.getFile().toPath();
			final Path artifactFolderPathInWOA = Util.folder( woa.javaPath().resolve( artifact.getGroupId().replace( ".", "/" ) + "/" + artifact.getArtifactId() + "/" + artifact.getVersion() ) );
			final Path artifactPathInWOA = artifactFolderPathInWOA.resolve( artifact.getFile().getName() );
			Util.copyFile( artifactPathInMavenRepository, artifactPathInWOA );
			classpathStrings.add( artifactPathInWOA.toString() );
		}

		// Copy WebServerResources from framework jars to the WOA
		for( final Artifact artifact : (Set<Artifact>)project.getArtifacts() ) {
			if( Util.shouldCopyWebServerResources( artifact.getFile() ) ) {
				final Path destinationPath = woa.contentsPath().resolve( "Frameworks" ).resolve( artifact.getArtifactId() + ".framework" );
				Util.copyWebServerResourcesFromJarToPath( artifact.getFile(), destinationPath );
			}
		}

		Util.copyContentsOfDirectoryToDirectory( project.getBasedir() + "/src/main/components", woa.resourcesPath().toString() );
		// FIXME: Flatten components
		Util.copyContentsOfDirectoryToDirectory( project.getBasedir() + "/src/main/" + woresourcesFolderName, woa.resourcesPath().toString() ); // FIXME: This should be woresources, here for compatibility
		// FIXME: Flatten resources (?)
		Util.copyContentsOfDirectoryToDirectory( project.getBasedir() + "/src/main/webserver-resources", woa.webServerResourcesPath().toString() );

		Util.writeStringToPath( Util.template( "info-plist" ), woa.contentsPath().resolve( "Info.plist" ) );

		Util.writeStringToPath( Util.template( "classpath" ), woa.macosPath().resolve( "MacOSClassPath.txt" ) );
		Util.writeStringToPath( Util.template( "classpath" ), woa.unixPath().resolve( "UNIXClassPath.txt" ) );
		// FIXME: Add Windows classpath
		Util.writeStringToPath( Util.template( "launch-script" ), woa.baseLaunchScriptPath() );
		Util.makeExecutable( woa.baseLaunchScriptPath() );
	}

	/**
	 * Our in-memory representation of the WOA bundle
	 */
	public static class WOA {

		private final String _applicationName;

		private final Path _woaPath;

		/**
		 * @return The WOA bundle [applicationName].woa in [containingDirectory]
		 */
		public static WOA getAtPath( final Path containingDirectory, final String applicationName ) {
			Objects.requireNonNull( containingDirectory );
			Objects.requireNonNull( applicationName );
			final Path woaPath = containingDirectory.resolve( applicationName + ".woa" );
			return new WOA( woaPath, applicationName );
		}

		private WOA( final Path woaPath, final String applicationName ) {
			Objects.requireNonNull( woaPath );
			Objects.requireNonNull( applicationName );
			_woaPath = Util.folder( woaPath );
			_applicationName = applicationName;
		}

		public Path woaPath() {
			return _woaPath;
		}

		public Path contentsPath() {
			return Util.folder( woaPath().resolve( "Contents" ) );
		}

		public Path macosPath() {
			return Util.folder( contentsPath().resolve( "MacOS" ) );
		}

		public Path unixPath() {
			return Util.folder( contentsPath().resolve( "UNIX" ) );
		}

		public Path windowsPath() {
			return Util.folder( contentsPath().resolve( "Windows" ) );
		}

		public Path resourcesPath() {
			return Util.folder( contentsPath().resolve( "Resources" ) );
		}

		public Path webServerResourcesPath() {
			return Util.folder( contentsPath().resolve( "WebServerResources" ) );
		}

		public Path javaPath() {
			return Util.folder( resourcesPath().resolve( "Java" ) );
		}

		public Path baseLaunchScriptPath() {
			return woaPath().resolve( _applicationName );
		}
	}
}