/*
 * Copyright 2007 Alin Dreghiciu, Stuart McCulloch.
 *
 * Licensed  under the  Apache License,  Version 2.0  (the "License");
 * you may not use  this file  except in  compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed  under the  License is distributed on an "AS IS" BASIS,
 * WITHOUT  WARRANTIES OR CONDITIONS  OF ANY KIND, either  express  or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.runner.platform.internal;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.xml.sax.SAXException;
import org.ops4j.io.FileUtils;
import org.ops4j.lang.NullArgumentException;
import org.ops4j.pax.runner.platform.BundleReference;
import org.ops4j.pax.runner.platform.Configuration;
import org.ops4j.pax.runner.platform.JavaRunner;
import org.ops4j.pax.runner.platform.LocalBundle;
import org.ops4j.pax.runner.platform.Platform;
import org.ops4j.pax.runner.platform.PlatformBuilder;
import org.ops4j.pax.runner.platform.PlatformContext;
import org.ops4j.pax.runner.platform.PlatformException;
import org.ops4j.util.property.DictionaryPropertyResolver;
import org.ops4j.util.property.PropertyResolver;

/**
 * Handles the workflow of creating the platform. Concrete platforms should implement only the PlatformBuilder
 * interface.
 * TODO Add unit tests
 *
 * @author Alin Dreghiciu, Stuart McCulloch
 * @since August 19, 2007
 */
public class PlatformImpl
    implements Platform
{

    /**
     * Logger.
     */
    private static final Log LOGGER = LogFactory.getLog( PlatformImpl.class );
    /**
     * relative location of ee packages root.
     */
    private static final String EE_FILES_ROOT = "META-INF/platform/ee/";
    /**
     * Concrete platform builder as equinox, felix, kf.
     */
    private final PlatformBuilder m_platformBuilder;
    /**
     * PropertyResolver to be used.Injected to allow a Managed Service implementation.
     */
    private PropertyResolver m_propertyResolver;
    /**
     * Current bundle context.
     */
    private final BundleContext m_bundleContext;
    /**
     * Mapping between upper case ee name and the relative location of packages file.
     */
    private Map<String, String> m_eeMappings;

    /**
     * Creates a new platform.
     *
     * @param platformBuilder concrete platform builder; mandatory
     * @param bundleContext   a bundle context
     */
    public PlatformImpl( final PlatformBuilder platformBuilder, final BundleContext bundleContext )
    {
        NullArgumentException.validateNotNull( platformBuilder, "Platform builder" );
        NullArgumentException.validateNotNull( bundleContext, "Bundle context" );
        m_platformBuilder = platformBuilder;
        m_bundleContext = bundleContext;
        // initialize ee mappings
        m_eeMappings = new HashMap<String, String>();
        m_eeMappings.put( "CDC-1.0/Foundation-1.0".toUpperCase(), "CDC-1.0/Foundation-1.0.packages" );
        m_eeMappings.put( "OSGi/Minimum-1.1".toUpperCase(), "OSGi/Minimum-1.1.packages" );
        m_eeMappings.put( "JRE-1.1".toUpperCase(), "JRE-1.1.packages" );
        m_eeMappings.put( "J2SE-1.2".toUpperCase(), "J2SE-1.2.packages" );
        m_eeMappings.put( "J2SE-1.3".toUpperCase(), "J2SE-1.3.packages" );
        m_eeMappings.put( "J2SE-1.4".toUpperCase(), "J2SE-1.4.packages" );
        m_eeMappings.put( "J2SE-1.5".toUpperCase(), "J2SE-1.5.packages" );
        m_eeMappings.put( "J2SE-1.6".toUpperCase(), "JavaSE-1.6.packages" );
        m_eeMappings.put( "JavaSE-1.6".toUpperCase(), "JavaSE-1.6.packages" );
        m_eeMappings.put( "PersonalJava-1.1".toUpperCase(), "PersonalJava-1.1.packages" );
        m_eeMappings.put( "PersonalJava-1.2".toUpperCase(), "PersonalJava-1.2.packages" );
        m_eeMappings.put( "CDC-1.0/PersonalBasis-1.0".toUpperCase(), "CDC-1.0/PersonalBasis-1.0.packages" );
        m_eeMappings.put( "CDC-1.0/PersonalJava-1.0".toUpperCase(), "CDC-1.0/PersonalJava-1.0.packages" );
    }

    /**
     * Sets the propertyResolver to use.
     *
     * @param propertyResolver a propertyResolver
     */
    public void setResolver( final PropertyResolver propertyResolver )
    {
        m_propertyResolver = propertyResolver;
    }

    /**
     * @see Platform#start(java.util.List,java.util.Properties,Dictionary)
     */
    public void start( final List<BundleReference> bundles, final Properties properties, final Dictionary config )
        throws PlatformException
    {
        start( bundles, properties, config, null );
    }

    /**
     * @see Platform#start(java.util.List,java.util.Properties,Dictionary,JavaRunner)
     */
    public void start( final List<BundleReference> bundles, final Properties properties, final Dictionary config, final JavaRunner runner )
        throws PlatformException
    {
        LOGGER.debug( "Preparing platform [" + this + "]" );
        // we should fail fast so let's do first what is easy
        final String mainClassName = m_platformBuilder.getMainClassName();
        if( mainClassName == null || mainClassName.trim().length() == 0 )
        {
            throw new PlatformException( "Main class of the platform cannot be null or empty" );
        }
        // create context
        final PlatformContext context = mandatory( "Platform context", createPlatformContext() );
        // set platform properties
        context.setProperties( properties );
        // create a configuration to look up the rest of the properties
        final Configuration configuration = mandatory( "Configuration", createConfiguration( config ) );
        context.setConfiguration( configuration );
        // create the platform definition from the configured url or from the platform builder
        final PlatformDefinition definition = mandatory( "Definition", createPlatformDefinition( configuration ) );
        LOGGER.debug( "Using platform definition [" + definition + "]" );
        // check out if the wrking folder must be removed first (clean start)
        final Boolean clean = configuration.isCleanStart();
        if( clean != null && clean )
        {
            FileUtils.delete( new File( configuration.getWorkingDirectory() ) );
        }
        // create a working directory on the file system
        final File workDir = mandatory( "Working dir", createWorkingDir( configuration.getWorkingDirectory() ) );
        LOGGER.debug( "Using working directory [" + workDir + "]" );
        context.setWorkingDirectory( workDir );
        final Boolean overwriteBundles = configuration.isOverwrite();
        final Boolean overwriteUserBundles = configuration.isOverwriteUserBundles();
        final Boolean overwriteSystemBundles = configuration.isOverwriteSystemBundles();
        final Boolean downloadFeeback = configuration.isDownloadFeedback();
        LOGGER.info( "Downloading bundles..." );
        // download system package
        LOGGER.debug( "Download system package" );
        final File systemFile = downloadSystemFile(
            workDir, definition, overwriteBundles || overwriteSystemBundles, downloadFeeback
        );
        // download the rest of the bundles
        final List<LocalBundle> bundlesToInstall = new ArrayList<LocalBundle>();
        LOGGER.debug( "Download platform bundles" );
        bundlesToInstall.addAll(
            downloadPlatformBundles(
                workDir, definition, context, overwriteBundles || overwriteSystemBundles, downloadFeeback
            )
        );
        LOGGER.debug( "Download bundles" );
        bundlesToInstall.addAll(
            downloadBundles(
                workDir, bundles, overwriteBundles || overwriteUserBundles, downloadFeeback
            )
        );
        context.setBundles( bundlesToInstall );
        context.setSystemPackages( createPackageList( configuration, definition ) );

        // and then ask the platform builder to prepare platform for start up (e.g. create configuration file)
        m_platformBuilder.prepare( context );

        // and finally start it up.
        if( null == runner )
        {
            final CommandLineBuilder commandLine = new CommandLineBuilder()
                .append( getJavaExecutable( configuration ) )
                .append( configuration.getVMOptions() )
                .append( m_platformBuilder.getVMOptions( context ) )
                .append( "-cp" )
                .append( systemFile.getAbsolutePath() + configuration.getClasspath() )
                .append( mainClassName )
                .append( m_platformBuilder.getArguments( context ) )
                .append( getFrameworkOptions() );

            LOGGER.debug( "Start command line [" + Arrays.toString( commandLine.toArray() ) + "]" );

            executeProcess( commandLine.toArray(), context.getWorkingDirectory() );
        }
        // or use an external Java runner service
        else
        {
            final CommandLineBuilder vmOptions = new CommandLineBuilder();
            vmOptions.append( configuration.getVMOptions() );
            vmOptions.append( m_platformBuilder.getVMOptions( context ) );

            final String[] classpath = ( systemFile.getAbsolutePath() + configuration.getClasspath() ).split( File.pathSeparator );

            final CommandLineBuilder programOptions = new CommandLineBuilder();
            programOptions.append( m_platformBuilder.getArguments( context ) );
            programOptions.append( getFrameworkOptions() );

            LOGGER.debug( "Start " + runner.getClass() + " [" + mainClassName + "]" );
            LOGGER.debug( "vmOptions      [" + Arrays.toString( vmOptions.toArray() ) + "]" );
            LOGGER.debug( "classpath      [" + Arrays.toString( classpath ) + "]" );
            LOGGER.debug( "programOptions [" + Arrays.toString( programOptions.toArray() ) + "]" );

            runner.exec( vmOptions.toArray(), classpath, mainClassName, programOptions.toArray() );
        }        
    }
        
    /**
     * Executes the process that contains the platform. Separated to be able to override in unit tests.
     *
     * @param commandLine      an array that makes up the command line
     * @param workingDirectory the working directory for th eprocess
     *
     * @throws PlatformException re-thrown if something goes wrong with executing the process
     */
    void executeProcess( final String[] commandLine, final File workingDirectory )
        throws PlatformException
    {
        final Process frameworkProcess;
        try
        {
            frameworkProcess = Runtime.getRuntime().exec( commandLine, null, workingDirectory );
        }
        catch( IOException e )
        {
            throw new PlatformException( "Could not start up the process", e );
        }

        LOGGER.info( "Starting platform [" + this + "]. Runner has successfully finished his job!" );

        Thread shutdownHook = createShutdownHook( frameworkProcess );
        Runtime.getRuntime().addShutdownHook( shutdownHook );
        LOGGER.debug( "Added shutdown hook." );

        try
        {
            LOGGER.debug( "Waiting for framework exit." );
            frameworkProcess.waitFor();
        }
        catch( InterruptedException e )
        {
            LOGGER.debug( e );
        }
        finally
        {
            try
            {
                Runtime.getRuntime().removeShutdownHook( shutdownHook );
                LOGGER.debug( "Early shutdown." );
                shutdownHook.run();
            }
            catch( IllegalStateException e )
            {
                LOGGER.debug( "Shutdown already in progress." );
            }
        }
    }

    /**
     * Create helper thread to safely shutdown the external framework process
     *
     * @param process framework process
     *
     * @return stream handler
     */
    private Thread createShutdownHook( final Process process )
    {
        LOGGER.debug( "Wrapping stream I/O." );

        final Pipe errPipe = new Pipe( process.getErrorStream(), System.err ).start( "Error pipe" );
        final Pipe outPipe = new Pipe( process.getInputStream(), System.out ).start( "Out pipe" );
        final Pipe inPipe = new Pipe( process.getOutputStream(), System.in ).start( "In pipe" );

        Thread shutdownHook = new Thread( new Runnable()
        {
            public void run()
            {
                try
                {
                    process.destroy();
                }
                catch( Exception e )
                {
                    // ignore if already shutting down
                }

                inPipe.stop();
                outPipe.stop();
                errPipe.stop();
            }
        }, "Pax-Runner shutdown hook" );

        return shutdownHook;
    }

    /**
     * Returns an array of framework options if set as system properties.
     *
     * @return framework options.
     */
    private String[] getFrameworkOptions()
    {
        // TODO check out if this should not be a platform start parameter
        // TODO what the hack are framework options?
        String[] options = new String[0];
        final String property = System.getProperty( "FRAMEWORK_OPTS" );
        if( property != null )
        {
            options = property.split( " " );
        }
        return options;
    }

    /**
     * Return path to java executable.
     *
     * @param configuration configuration in use
     *
     * @return path to java executable
     *
     * @throws PlatformException if java home could not be located
     */
    String getJavaExecutable( final Configuration configuration )
        throws PlatformException
    {
        final String javaHome = configuration.getJavaHome();
        if( javaHome == null )
        {
            throw new PlatformException( "JAVA_HOME is not set." );
        }
        return javaHome + "/bin/java";
    }

    /**
     * Downloads the bundles that will be installed to the working directory.
     *
     * @param bundles         url of bundles to be installed
     * @param workDir         the directory where to download bundles
     * @param overwrite       if the bundles should be overwritten
     * @param downloadFeeback whether or not downloading process should display fne grained progres info
     *
     * @return a list of downloaded files
     *
     * @throws PlatformException re-thrown
     */
    private List<LocalBundle> downloadBundles( final File workDir, final List<BundleReference> bundles,
                                               final Boolean overwrite, final boolean downloadFeeback )
        throws PlatformException
    {
        final List<LocalBundle> localBundles = new ArrayList<LocalBundle>();
        if( bundles != null )
        {
            for( BundleReference reference : bundles )
            {
                final URL url = reference.getURL();
                if( url == null )
                {
                    throw new PlatformException( "Invalid url in bundle refrence [" + reference + "]" );
                }
                localBundles.add(
                    new LocalBundleImpl(
                        reference,
                        download(
                            workDir,
                            url,
                            reference.getName(),
                            overwrite || reference.shouldUpdate(),
                            true,
                            downloadFeeback
                        )
                    )
                );
            }
        }
        return localBundles;
    }

    /**
     * Downsloads platform bundles to working dir.
     *
     * @param workDir         the directory where to download bundles
     * @param definition      to take the system package
     * @param platformContext current platform context
     * @param overwrite       if the bundles should be overwritten
     * @param downloadFeeback whether or not downloading process should display fne grained progres info
     *
     * @return a list of downloaded files
     *
     * @throws PlatformException re-thrown
     */
    private List<LocalBundle> downloadPlatformBundles( final File workDir, final PlatformDefinition definition,
                                                       final PlatformContext platformContext, final Boolean overwrite,
                                                       final boolean downloadFeeback )
        throws PlatformException
    {
        final StringBuilder profiles = new StringBuilder();
        final String userProfiles = platformContext.getConfiguration().getProfiles();
        if( userProfiles != null && userProfiles.trim().length() > 0 )
        {
            profiles.append( userProfiles );
        }
        final String builderProfile = m_platformBuilder.getRequiredProfile( platformContext );
        if( builderProfile != null && builderProfile.trim().length() > 0 )
        {
            if( profiles.length() > 0 )
            {
                profiles.append( "," );
            }
            profiles.append( builderProfile );
        }
        return downloadBundles(
            workDir, definition.getPlatformBundles( profiles.toString() ), overwrite, downloadFeeback
        );
    }

    /**
     * Downloads the system file.
     *
     * @param workDir         the directory where to download bundles
     * @param definition      to take the system package
     * @param overwrite       if the bundles should be overwritten
     * @param downloadFeeback whether or not downloading process should display fne grained progres info
     *
     * @return the system file
     *
     * @throws PlatformException re-thrown
     */
    private File downloadSystemFile( final File workDir, final PlatformDefinition definition, final Boolean overwrite,
                                     final boolean downloadFeeback )
        throws PlatformException
    {
        return download(
            workDir, definition.getSystemPackage(), definition.getSystemPackageName(), overwrite, false, downloadFeeback
        );
    }

    /**
     * Downloads files from urls.
     *
     * @param workDir         the directory where to download bundles
     * @param url             of the file to be downloaded
     * @param displayName     to be shown during download
     * @param overwrite       if the bundles should be overwritten
     * @param checkAttributes whether or not to check attributes in the manifest
     * @param downloadFeeback whether or not downloading process should display fne grained progres info
     *
     * @return the File corresponding to the downloaded file.
     *
     * @throws PlatformException if the url could not be downloaded
     */
    private File download( final File workDir, final URL url, final String displayName, final Boolean overwrite,
                           final boolean checkAttributes, final boolean downloadFeeback )
        throws PlatformException
    {
        LOGGER.debug( "Downloading [" + url + "]" );
        File downloadedBundlesFile = new File( workDir, "bundles/downloaded_bundles.properties" );
        Properties fileNamesForUrls = loadProperties( downloadedBundlesFile );

        String downloadedFileName = fileNamesForUrls.getProperty( url.toExternalForm() );
        String hashFileName = "" + url.toExternalForm().hashCode();
        if( downloadedFileName == null )
        {
            // destination will be made based on the hashcode of the url to be downloaded
            downloadedFileName = hashFileName + ".jar";

        }
        File destination = new File( workDir, "bundles/" + downloadedFileName );

        // download the bundle only if is a forced overwrite or the file does not exist or the file is there but is
        // invalid
        boolean forceOverwrite = overwrite || !destination.exists();
        if( !forceOverwrite )
        {
            try
            {
                String newFileName = validateBundleAndGetFilename( url, destination, hashFileName, checkAttributes );
                if( !destination.getName().equals( newFileName ) )
                {
                    throw new PlatformException( "File " + destination + " should have name " + newFileName );
                }
            }
            catch( PlatformException ignore )
            {
                forceOverwrite = true;
            }
        }
        if( forceOverwrite )
        {
            try
            {
                LOGGER.debug( "Creating new file at destination: " + destination.getAbsolutePath() );
                destination.getParentFile().mkdirs();
                destination.createNewFile();
                BufferedOutputStream os = null;
                try
                {
                    os = new BufferedOutputStream( new FileOutputStream( destination ) );
                    StreamUtils.ProgressBar progressBar = null;
                    if( LOGGER.isInfoEnabled() )
                    {
                        if( downloadFeeback )
                        {
                            progressBar = new StreamUtils.FineGrainedProgressBar( displayName );
                        }
                        else
                        {
                            progressBar = new StreamUtils.CoarseGrainedProgressBar( displayName );
                        }
                    }
                    StreamUtils.streamCopy( url, os, progressBar );
                    LOGGER.debug( "Succesfully downloaded to [" + destination + "]" );
                }
                finally
                {
                    if( os != null )
                    {
                        os.close();
                    }
                }
            }
            catch( IOException e )
            {
                throw new PlatformException( "[" + url + "] could not be downloaded", e );
            }
        }
        String newFileName = validateBundleAndGetFilename( url, destination, hashFileName, checkAttributes );
        File newDestination = new File( destination.getParentFile(), newFileName );
        if( !newFileName.equals( destination.getName() ) )
        {
            if( newDestination.exists() )
            {
                if( !newDestination.delete() )
                {
                    throw new PlatformException( "Cannot delete " + newDestination );
                }
            }
            if( !destination.renameTo( newDestination ) )
            {
                throw new PlatformException( "Cannot rename " + destination + " to " + newDestination );
            }
            fileNamesForUrls.setProperty( url.toExternalForm(), newFileName );
            saveProperties( fileNamesForUrls, downloadedBundlesFile );
        }

        return newDestination;
    }

    private Properties loadProperties( File file )
    {
        Properties properties = new Properties();
        FileInputStream in = null;
        try
        {
            in = new FileInputStream( file );
            properties.load( in );
            return properties;
        }
        catch( IOException e )
        {
            return properties;
        }
        finally
        {
            if( in != null )
            {
                try
                {
                    in.close();
                }
                catch( IOException e )
                {
                    // ignore
                }
            }
        }
    }

    private void saveProperties( Properties properties, File file )
        throws PlatformException
    {
        FileOutputStream os = null;
        try
        {
            os = new FileOutputStream( file );
            properties.store( os, "" );
        }
        catch( IOException e )
        {
            throw new PlatformException( "Cannot store properties " + file, e );
        }
        finally
        {
            if( os != null )
            {
                try
                {
                    os.close();
                }
                catch( IOException e )
                {
                    // ignore
                }
            }

        }
    }

    /**
     * Validate that the file is an valid bundle. A valid bundle will be a loadable jar file that has manifes and the
     * manifest contains at least an entry for Bundle-SymboliName.
     *
     * @param url                       original url from where the bundle was created.
     * @param file                      file to be validated
     * @param defaultBundleSymbolicName default bundle symbolic name to be used if manifest does not have a bundle
     *                                  symbolic name
     * @param checkAttributes           whether or not to check attributes in the manifest
     *
     * @return file name based on bundle symbolic name and version
     *
     * @throws PlatformException if the jar is not a valid bundle
     */
    String validateBundleAndGetFilename( final URL url,
                                         final File file,
                                         final String defaultBundleSymbolicName,
                                         final boolean checkAttributes )
        throws PlatformException
    {
        JarFile jar = null;
        try
        {
            // verify that is a valid jar. Do not verify that is signed (the false param).
            jar = new JarFile( file, false );
            final Manifest manifest = jar.getManifest();
            if( manifest == null )
            {
                throw new PlatformException( "[" + url + "] is not a valid bundle" );
            }
            String bundleSymbolicName = manifest.getMainAttributes().getValue( Constants.BUNDLE_SYMBOLICNAME );
            String bundleName = manifest.getMainAttributes().getValue( Constants.BUNDLE_NAME );
            String bundleVersion = manifest.getMainAttributes().getValue( Constants.BUNDLE_VERSION );
            if( checkAttributes )
            {
                if( bundleSymbolicName == null && bundleName == null )
                {
                    throw new PlatformException( "[" + url + "] is not a valid bundle" );
                }
            }
            if( bundleSymbolicName == null )
            {
                bundleSymbolicName = defaultBundleSymbolicName;
            }
            else
            {
                // remove directives like "; singleton:=true"  
                int semicolonPos = bundleSymbolicName.indexOf( ";" );
                if( semicolonPos > 0 )
                {
                    bundleSymbolicName = bundleSymbolicName.substring( 0, semicolonPos );
                }
            }
            if( bundleVersion == null )
            {
                bundleVersion = "0.0.0";
            }
            return bundleSymbolicName + "_" + bundleVersion + ".jar";
        }
        catch( IOException e )
        {
            throw new PlatformException( "[" + url + "] is not a valid bundle", e );
        }
        finally
        {
            if( jar != null )
            {
                try
                {
                    jar.close();
                }
                catch( IOException ignore )
                {
                    // just ignore as this is less probably to happen.
                }
            }
        }
    }

    /**
     * Creates a working directory.
     *
     * @param path path to working directory.
     *
     * @return a working directory.
     */
    private File createWorkingDir( final String path )
    {
        File workDir = new File( path );
        workDir.mkdirs();
        return workDir;
    }

    /**
     * Returns a comma separated list of system packages, constructed from:<br/> 1. execution envoronment option<br/>
     * 1.1. if option vale is NONE => no packages<br/> 1.2. if option is one of the standard ee => use the
     * corresponding package list<br/> 1.3. if not set => determine based on current jvm<br/> 1.4. if option is set
     * and option 1.2 is true then the option value must be an url of a file that contains pkgs.<br/> 2. + additional
     * packages from systemPackages option<br/> 3. + list of packages contributed by the platform (see felix case)<br/>
     *
     * @param configuration      configuration in use
     * @param platformDefinition a platform definition
     *
     * @return comma separated list of packages
     *
     * @throws org.ops4j.pax.runner.platform.PlatformException
     *          if packages file not found or can't be read
     */
    String createPackageList( final Configuration configuration, final PlatformDefinition platformDefinition )
        throws PlatformException
    {
        final StringBuffer packages = new StringBuffer();
        final String ee = configuration.getExecutionEnvironment();
        if( !"NONE".equalsIgnoreCase( ee ) )
        {
            // we make an union of the packages form each ee so let's have a unique set for it
            final Set<String> unique = new HashSet<String>();
            for( String segment : ee.split( "," ) )
            {
                final URL url = discoverExecutionEnvironmentURL( segment );
                BufferedReader reader = null;
                try
                {
                    try
                    {
                        reader = new BufferedReader( new InputStreamReader( url.openStream() ) );
                        String line;
                        while( ( line = reader.readLine() ) != null )
                        {
                            line = line.trim();
                            // dont add empty lines and packages that we already have
                            if( line.length() > 0 && !unique.contains( line ) )
                            {
                                if( packages.length() > 0 )
                                {
                                    packages.append( ", " );
                                }
                                packages.append( line );
                                unique.add( line );
                            }
                        }
                    }
                    finally
                    {
                        if( reader != null )
                        {
                            reader.close();
                        }
                    }
                }
                catch( IOException e )
                {
                    throw new PlatformException( "Could not read packages from execution environment", e );
                }
            }
        }
        // append used defined packages
        final String userPackages = configuration.getSystemPackages();
        if( userPackages != null && userPackages.trim().length() > 0 )
        {
            if( packages.length() > 0 )
            {
                packages.append( ", " );
            }
            packages.append( userPackages );
        }
        // append platform specific packages
        final String platformPackages = platformDefinition.getPackages();
        if( platformPackages != null && platformPackages.trim().length() > 0 )
        {
            if( packages.length() > 0 )
            {
                packages.append( ", " );
            }
            packages.append( platformPackages );
        }
        return packages.toString();
    }

    /**
     * Retruns the url of a file containing the execution environment packages
     *
     * @param ee execution environment name
     *
     * @return url of the file
     *
     * @throws PlatformException if execution environment could not be determined or found
     */
    private URL discoverExecutionEnvironmentURL( final String ee )
        throws PlatformException
    {
        URL url;
        final String relativeFileName = m_eeMappings.get( ee.toUpperCase() );
        if( relativeFileName != null )
        {
            url = m_bundleContext.getBundle().getResource( EE_FILES_ROOT + relativeFileName );
            if( url == null )
            {
                throw new PlatformException( "Execution environment [" + ee + "] not supported" );
            }
            LOGGER.info( "Execution environment [" + ee + "]" );
        }
        else
        {
            try
            {
                url = new URL( ee );
                LOGGER.info( "Execution environment [" + url.toExternalForm() + "]" );
            }
            catch( MalformedURLException e )
            {
                throw new PlatformException( "Execution environment [" + ee + "] could not be found", e );
            }
        }
        return url;
    }

    /**
     * Configuration factory method.
     *
     * @param config service configuration properties
     *
     * @return a configuration
     */
    Configuration createConfiguration( final Dictionary config )
    {
        PropertyResolver propertyResolver = m_propertyResolver;
        if( config != null )
        {
            propertyResolver = new DictionaryPropertyResolver( config, m_propertyResolver );
        }
        return new ConfigurationImpl( propertyResolver );
    }

    /**
     * Platform definition factory method. First looks for a configured definition url. If not found will use the
     * platform builder.
     *
     * @param configuration a platform configuration
     *
     * @return a platform definition
     *
     * @throws org.ops4j.pax.runner.platform.PlatformException
     *          in case of an invalid platform definition
     */
    PlatformDefinition createPlatformDefinition( final Configuration configuration )
        throws PlatformException
    {
        NullArgumentException.validateNotNull( configuration, "Configuration" );
        try
        {
            final URL definitionURL = configuration.getDefinitionURL();
            InputStream inputStream = null;
            if( definitionURL != null )
            {
                inputStream = definitionURL.openStream();
            }
            if( inputStream == null )
            {
                inputStream = m_platformBuilder.getDefinition();
            }
            return new PlatformDefinitionImpl( inputStream, configuration.getProfileStartLevel() );
        }
        catch( IOException e )
        {
            throw new PlatformException( "Invalid platform definition", e );
        }
        catch( ParserConfigurationException e )
        {
            throw new PlatformException( "Invalid platform definition", e );
        }
        catch( SAXException e )
        {
            throw new PlatformException( "Invalid platform definition", e );
        }
    }

    /**
     * Platform context context factory methods.
     *
     * @return a platform context.
     */
    PlatformContext createPlatformContext()
    {
        return new PlatformContextImpl();
    }

    /**
     * Check if the variable is not null. If null throw an illegal argument exception.
     *
     * @param object the object to be checked
     * @param name   a nem to be used when making up the exception message
     *
     * @return the passed object if not null
     */
    private <T> T mandatory( final String name, final T object )
    {
        if( object == null )
        {
            throw new IllegalStateException( name + " cannot be null" );
        }
        return object;
    }

    /**
     * @see Object#toString()
     */
    public String toString()
    {
        return m_platformBuilder.toString();
    }

}
