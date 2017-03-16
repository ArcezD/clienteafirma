package es.gob.afirma.standalone.ui.restoreconfig;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.swing.JOptionPane;

import es.gob.afirma.core.misc.BoundedBufferedReader;
import es.gob.afirma.core.misc.Platform;
import es.gob.afirma.keystores.mozilla.MozillaKeyStoreUtilities;
import es.gob.afirma.standalone.SimpleAfirmaMessages;

/** Configurador para instalar un certificado SSL de confianza en Mozilla NSS.
 * @author Tom&aacute;s Garc&iacute;a-Mer&aacute;s. */
final class RestoreConfigFirefox {

	static final class MozillaProfileNotFoundException extends Exception {
		private static final long serialVersionUID = -2329746920496661591L;
	}

	private static final Logger LOGGER = Logger.getLogger("es.gob.afirma"); //$NON-NLS-1$

	private static final String FILE_AUTOFIRMA_CERTIFICATE = "AutoFirma_ROOT.cer"; //$NON-NLS-1$
	static final String DIR_CERTUTIL = "certutil"; //$NON-NLS-1$
	private static final String LINUX_UNINSTALLSCRIPT_NAME = "uninstall.sh"; //$NON-NLS-1$
	private static final String LINUX_SCRIPT_NAME = "install.sh"; //$NON-NLS-1$
	private static final String LINUX_MOZILLA_PATH = "/.mozilla/firefox/profiles.ini";//$NON-NLS-1$
	private static final String LINUX_CHROME_PATH = "/.pki/nssdb";//$NON-NLS-1$
	private static final String LINUX_CHROMIUM_PREFS_PATH = "/.config/chromium/Local State";//$NON-NLS-1$
	private static final String LINUX_CHROME_PREFS_PATH = "/.config/google-chrome/Local State";//$NON-NLS-1$
	private static final String MACOSX_MOZILLA_PATH = "/Library/Application Support/firefox/profiles.ini";//$NON-NLS-1$
	private static String WINDOWS_MOZILLA_PATH = "\\AppData\\Roaming\\Mozilla\\Firefox\\profiles.ini"; //$NON-NLS-1$
	private static final String GET_USER_SCRIPT = "scriptGetUsers";//$NON-NLS-1$
	private static final String SCRIPT_EXT = ".sh";//$NON-NLS-1$
	static final String CERTUTIL_EXE;
	private static final String FILE_CERTUTIL;
	private static final String RESOURCE_BASE;
	
	private static String USERS_WINDOWS_PATH;
	static {
		try {
			USERS_WINDOWS_PATH = new File(System.getProperty("user.home")).getParentFile().getAbsolutePath(); //$NON-NLS-1$;
		}
		catch (final Exception e) {
			LOGGER.warning("No se ha podido identificar el directorio de usuarios: " + e); //$NON-NLS-1$
			USERS_WINDOWS_PATH = "C:/Users"; //$NON-NLS-1$
		}
	}
	
	/** Nombre del usuario por defecto en Windows. Este usuario es el que se usa como base para
	 * crear nuevos usuarios y no se deber&iacute;a tocar. */
	private static String DEFAULT_WINDOWS_USER_NAME = "Default"; //$NON-NLS-1$

	static {
		switch(Platform.getOS()) {
		case WINDOWS:
			CERTUTIL_EXE = "certutil.exe"; //$NON-NLS-1$
			FILE_CERTUTIL = "certutil.windows.zip"; //$NON-NLS-1$
			RESOURCE_BASE = "/windows/"; //$NON-NLS-1$
			break;
		case MACOSX:
			CERTUTIL_EXE = "certutil"; //$NON-NLS-1$
			FILE_CERTUTIL = "certutil.osx.zip"; //$NON-NLS-1$
			RESOURCE_BASE = "/osx/"; //$NON-NLS-1$
			break;
		case LINUX:
			CERTUTIL_EXE = "certutil"; //$NON-NLS-1$
			FILE_CERTUTIL = "certutil.linux.zip"; //$NON-NLS-1$
			RESOURCE_BASE = "/linux/"; //$NON-NLS-1$
			break;
		default:
			throw new IllegalStateException(
					"Sistema operativo no soportado: " + Platform.getOS() //$NON-NLS-1$
					);
		}
	}

	private RestoreConfigFirefox() {
		// No instanciable
	}

	static void installRootCAMozillaKeyStore(final File targetDir) throws MozillaProfileNotFoundException,
	                                                                      IOException {
		final ArrayList<File> firefoxProfilesDir = getFirefoxProfilesDir();
		if (firefoxProfilesDir == null || firefoxProfilesDir.isEmpty()) {
			throw new MozillaProfileNotFoundException();
		}
		
		for (final File firefoxDir : firefoxProfilesDir) {
			RestoreConfigFirefox.importCARootOnFirefoxKeyStore(
				targetDir,
				firefoxDir
			);
		}

	}

	/** Genera el script que elimina el warning al ejecutar AutoFirma desde Chrome para LINUX.
	 * En linux genera el script que hay que ejecutar para realizar la instalaci&oacute;n pero no lo ejecuta, de eso se encarga el instalador Debian.
	 * @param targetDir Directorio de instalaci&oacute;n del sistema
	 * @param userDir Directorio de usuario dentro del sistema operativo.
	 * @param browserPath Directorio de configuraci&oacute;n de Chromium o Google Chrome.
	 *  <ul>
	 *   <li>En LINUX contiene el contenido del script a ejecutar.</li>
	 * </ul> */
	private static void createScriptsRemoveExecutionWarningInChrome(final File targetDir, final String userDir, final String browserPath) {
		final String[] commandInstall = new String[] {
				"sed", //$NON-NLS-1$
				"s/\\\"protocol_handler\\\":{\\\"excluded_schemes\\\":{/\\\"protocol_handler\\\":{\\\"excluded_schemes\\\":{\\\"afirma\\\":false,/g", //$NON-NLS-1$
				escapePath(userDir + browserPath),
				">", //$NON-NLS-1$
				escapePath(userDir + browserPath) + "1", //$NON-NLS-1$
		};

		final String[] commandUninstall = new String[] {
				"sed", //$NON-NLS-1$
				"s/\\\"afirma\\\":false,//g", //$NON-NLS-1$
				escapePath(userDir + browserPath),
				">", //$NON-NLS-1$
				escapePath(userDir + browserPath) + "1", //$NON-NLS-1$
		};

		//Se reemplaza el fichero generado por el original
		final String[] commandCopy = new String[] {
				"\\cp", //$NON-NLS-1$
				escapePath(userDir + browserPath) + "1", //$NON-NLS-1$
				escapePath(userDir + browserPath),
		};

		// Generamos el script de instalacion y desistalacion
		try {

			final StringBuilder sb = new StringBuilder();
			for (final String s : commandInstall) {
				sb.append(s);
				sb.append(' ');
			}

			final StringBuilder uninstall = new StringBuilder();
			for (final String s : commandUninstall) {
				uninstall.append(s);
				uninstall.append(' ');
			}
			uninstall.append("\n"); //$NON-NLS-1$
			sb.append("\n"); //$NON-NLS-1$

			for (final String s : commandCopy) {
				sb.append(s);
				sb.append(' ');
			}
			for (final String s : commandCopy) {
				uninstall.append(s);
				uninstall.append(' ');
			}
			String path = null;
			String uninstallPath = null;
			sb.append("\n"); //$NON-NLS-1$
			uninstall.append("\n"); //$NON-NLS-1$

			// Obtenemos la ruta de los scripts
			path = new File(targetDir, LINUX_SCRIPT_NAME).getAbsolutePath();
			uninstallPath = new File(targetDir, LINUX_UNINSTALLSCRIPT_NAME).getAbsolutePath();
			final File installScript = new File(path);
			final File uninstallScript = new File(uninstallPath);

			try (
					final FileOutputStream fout = new FileOutputStream(installScript, true);
					final FileOutputStream foutUninstall = new FileOutputStream(
							uninstallScript, true
							);
					) {
				fout.write(sb.toString().getBytes());
				foutUninstall.write(uninstall.toString().getBytes());
			}
		}
		catch (final Exception e) {
			LOGGER.severe(
					"Excepcion en la creacion del script linux para la modificacion del fichero de protocolos de Google Chrome: " + e //$NON-NLS-1$
					);
		}

	}

	/** Genera el script que elimina el warning al ejecutar AutoFirma desde Chrome para LINUX.
	 * En linux genera el script que hay que ejecutar para realizar la instalaci&oacute;n pero no lo ejecuta, de eso se encarga el instalador Debian.
	 * @param targetDir Directorio de instalaci&oacute;n del sistema
	 * @param command Usado para sacar los directorios de usuario dentro del sistema operativo.
	 *  <ul>
	 * <li>En LINUX contiene el contenido del script a ejecutar.</li>
	 * </ul>
	 */
	static void removeAppExecutionWarningInChrome(final File targetDir, final String[] command) {

		// sacamos el listado de usuarios de la aplicacion
		final List<String> usersDirs = getSystemUsersHomes(command);

		for ( final String userDir : usersDirs) {
			// Montamos el script de instalacion y desinstalacion que
			// incluya el protocolo "afirma" en el fichero Local State
			if ( Platform.OS.LINUX.equals(Platform.getOS()) ) {
				final File fileChrome = new File(escapePath(userDir) + LINUX_CHROME_PREFS_PATH);
				final File fileChromium = new File(escapePath(userDir) + LINUX_CHROMIUM_PREFS_PATH);
				if( fileChrome.isFile() ) {
					createScriptsRemoveExecutionWarningInChrome(targetDir, userDir, LINUX_CHROME_PREFS_PATH);
				}
				if ( fileChromium.isFile() ) {
					createScriptsRemoveExecutionWarningInChrome(targetDir, userDir, LINUX_CHROMIUM_PREFS_PATH);
				}
			}
		}
	}

	/**
	 * Instala el certificado en Chrome para LINUX.
	 * @param targetDir Directorio de instalaci&oacute;n del sistema
	 * @param command Usado para sacar los directorios de usuario dentro del sistema operativo.
	 *  <ul>
	 * <li>En LINUX contiene el contenido del script a ejecutar.</li>
	 * </ul>
	 * @throws IOException Cuando ocurre un error en el tratamiento de datos.
	 */
	static void installRootCAChromeKeyStore(final File targetDir, final String[] command )
			throws IOException {

		// sacamos el listado de usuarios de la aplicacion
		final List<String> usersDirs = getSystemUsersHomes(command);

		for ( final String userDir : usersDirs) {
			final File file = new File(escapePath(userDir) + LINUX_CHROME_PATH);
			if( file.isDirectory()) {
				//Usamos el comando para importar en Chrome en Linux
				if ( Platform.OS.LINUX.equals(Platform.getOS()) ) {
					final String[] certutilCommands = new String[] {
							CERTUTIL_EXE, // 0
							"-d", //$NON-NLS-1$ // 1
							"sql:" + escapePath(userDir) + LINUX_CHROME_PATH, //$NON-NLS-1$ // 2
							"-A", //$NON-NLS-1$ // 3
							"-n", //$NON-NLS-1$ // 4
							"\"" + RestoreConfigUtil.CERT_ALIAS + "\"", //$NON-NLS-1$ //$NON-NLS-2$ // 5
							"-i", //$NON-NLS-1$ // 6
							escapePath(new File(targetDir, FILE_AUTOFIRMA_CERTIFICATE).getAbsolutePath()), // 7
							"-t", //$NON-NLS-1$ // 8
							"\"TCP,TCP,TCP\"" //$NON-NLS-1$ // 9
					};
					execCommandLineCertUtil(certutilCommands, true);
					
				}
			}
		}
	}

	/**
	 * Genera el script de instalaci&oacute; del certificado en firefox para MacOSX y LINUX.
	 * En linux genera el script que hay que ejecutar para realizar la instalaci&oacute;n pero no lo ejecuta, de eso se encarga el instalador Debian.
	 * En MacOSX el script se ejecuta a la vuelta de este m&eacute;todo.
	 * @param targetDir Directorio de instalaci&oacute;n del sistema
	 * @param command Usado para sacar los directorios de usuario dentro del sistema operativo.
	 *  <ul>
	 * <li>En LINUX contiene el contenido del script a ejecutar.</li>
	 * <li>En MacOSX contiene la ruta del script a ejecutar.</li>
	 * </ul>
	 * @throws MozillaProfileNotFoundException No se ha encontrado el directorio de perfiles de Mozilla.
	 * @throws IOException Cuando ocurre un error en el tratamiento de datos.
	 */
	static void installRootCAMozillaKeyStore(final File targetDir, final String[] command )
			throws MozillaProfileNotFoundException, IOException {

		// sacamos el listado de usuarios de la aplicacion
		final List<String> usersDirs = getSystemUsersHomes(command);

		// dados los usuarios sacamos el directorio de perfiles de mozilla en caso de que lo tengan
		final List <File> mozillaUsersProfilesPath = getMozillaUsersProfilesPath(usersDirs);
		// para cada usuario tenemos sus distintos directorios de perfiles
		final Set <File> profiles = getProfiles(mozillaUsersProfilesPath);
		if (profiles.isEmpty()){
			throw new MozillaProfileNotFoundException();
		}
//		if(!Platform.getOS().equals(Platform.OS.LINUX)) {
//			copyConfigurationFiles(targetDir);
//		}

		RestoreConfigFirefox.importCARootOnFirefoxKeyStore(targetDir, profiles);

	}


	public static void uninstallRootCAMozillaKeyStore(final File targetDir) {
	
		try {
			executeCertUtilToDelete(targetDir);
		}
		catch (final Exception e) {
			LOGGER.warning("No se pudo desinstalar el certificado SSL raiz del almacen de Mozilla Firefox: " + e); //$NON-NLS-1$
		}
		
	}


	static void generateUninstallScriptMac(final File targetDir) throws IOException {

		final StringBuilder sb = new StringBuilder(RestoreConfigMacOSX.OSX_GET_USERS_COMMAND);
		final File scriptFile = File.createTempFile(GET_USER_SCRIPT, SCRIPT_EXT);

		try {
			RestoreConfigMacOSX.writeScriptFile(scriptFile.getAbsolutePath(), sb, true);
		} catch (final IOException e) {
			LOGGER.log(Level.WARNING, " Ha ocurrido un error al generar el script de desinstalacion: " + e, e); //$NON-NLS-1$
		}
		RestoreConfigMacOSX.addExexPermissionsToFile(scriptFile);

		// sacamos el listado de usuarios de la aplicacion
		final List<String> usersDirs = getSystemUsersHomes(new String[]{scriptFile.getAbsolutePath()});

		scriptFile.delete();

		// dados los usuarios sacamos el directorio de perfiles de mozilla en caso de que lo tengan
		final List <File> mozillaUsersProfilesPath = getMozillaUsersProfilesPath(usersDirs);
		// Si no se encuentra el fichero de perfiles de firefox, abortamos la operacion
		if (mozillaUsersProfilesPath == null) {
			LOGGER.info("No se encuentra el fichero de perfiles de Firefox, por lo que no se desinstalaran certificados"); //$NON-NLS-1$
			return;
		}

		// para cada usuario tenemos sus distintos directorios de perfiles
		final Set <File> profiles = getProfiles(mozillaUsersProfilesPath);
		if (profiles.isEmpty()) {
			LOGGER.info("No se han encontrado perfiles de Mozilla de los que desinstalar los certificados"); //$NON-NLS-1$
			return;
		}

		final File certutilFile = new File(targetDir, DIR_CERTUTIL + File.separator + CERTUTIL_EXE);

		if (!certutilFile.exists() || !certutilFile.isFile() || !certutilFile.canExecute()) {
			throw new IOException("No se encuentra o no se puede leer el ejecutable para la instalacion en Firefox"); //$NON-NLS-1$
		}

		for (final File profile : profiles) {
			if (!profile.isDirectory()) {
				continue;
			}

			final String scriptUninstall = "max=$(" //$NON-NLS-1$
			+ escapePath(certutilFile.getAbsolutePath())
			+ " -L -d " //$NON-NLS-1$
			+ escapePath(profile.getAbsolutePath())
			+ " | grep AutoFirma | wc -l);for ((i=0; i<$max; i++));do " //$NON-NLS-1$
			+ escapePath(certutilFile.getAbsolutePath())
			+ " -D -d " //$NON-NLS-1$
			+ escapePath(profile.getAbsolutePath())
			+ " -n \"SocketAutoFirma\";done"; //$NON-NLS-1$
			final String[] certutilCommands = scriptUninstall.split(" "); //$NON-NLS-1$

			execCommandLineCertUtil(certutilCommands, true);

		}

	}

	/** Elimina la carpeta certutil generada durante el proceso de instalaci&oacute;n.
	 * @param targetDir Directorio en el que se copia certUtil. */
	static void removeConfigurationFiles(final File targetDir) {
		if (!targetDir.exists()) {
			return;
		}
		RestoreConfigFirefox.deleteConfigDir(targetDir);
	}

	static void addExexPermissionsToFile(final File f) {
		final Set<PosixFilePermission> perms = new HashSet<>();
		perms.add(PosixFilePermission.OWNER_EXECUTE);
		perms.add(PosixFilePermission.GROUP_EXECUTE);
		perms.add(PosixFilePermission.OTHERS_EXECUTE);
		perms.add(PosixFilePermission.OWNER_READ);
		perms.add(PosixFilePermission.GROUP_READ);
		perms.add(PosixFilePermission.OTHERS_READ);
		perms.add(PosixFilePermission.OWNER_WRITE);
		perms.add(PosixFilePermission.GROUP_WRITE);
		perms.add(PosixFilePermission.OTHERS_WRITE);
		try {
			Files.setPosixFilePermissions(
				Paths.get(f.getAbsolutePath()),
				perms
			);
		}
		catch (final Exception e) {
			LOGGER.warning(
				"No se ha podido dar permiso de ejecucion a '" + f.getAbsolutePath() + "': " + e//$NON-NLS-1$ //$NON-NLS-2$
			);
		}
	}


	private static String escapePath(final String path) {
		if (path == null) {
			throw new IllegalArgumentException(
				"La ruta a 'escapar' no puede ser nula" //$NON-NLS-1$
			);
		}
		if (Platform.OS.WINDOWS.equals(Platform.getOS())) {
			if (path.contains(" ")) { //$NON-NLS-1$
				return "\"" + path + "\""; //$NON-NLS-1$ //$NON-NLS-2$
			}
			return path;
		}
		return path.replace(" ", "\\ "); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/** Copiamos el certificado en un directorio para que Mozilla CertUtil pueda usarlo.
	 * @param certUtilAbsolutePath Ruta del ejecutable CertUtil.
	 * @return certutilFile Fichero ejecutable CertUtil.
	 * @throws IOException Se lanza cuando hay un problema con el fichero CertUtil. */
	public static File InstallCerFile(final String certUtilAbsolutePath) throws IOException {

		//En linux se trabaja con la dependencia del certutil
		if (!Platform.OS.LINUX.equals(Platform.getOS()) ) {
			final File certutilFile = new File(certUtilAbsolutePath);

			if (!certutilFile.exists() || !certutilFile.isFile()) {
				throw new IOException("No se encuentra el ejecutable CertUtil para la instalacion en Firefox" //$NON-NLS-1$
						);
			}

			if (!certutilFile.canExecute() && Platform.OS.MACOSX.equals(Platform.getOS())) {
				RestoreConfigMacOSX.addExexPermissionsToAllFilesOnDirectory(certutilFile.getParentFile());
			}

			if (!certutilFile.canExecute()) {
				throw new IOException("No hay permisos de ejecucion para Mozilla CertUtil" //$NON-NLS-1$
						);
			}

			return certutilFile;
		}
		return null;
	}

	/** Ejecuta la utilidad Mozilla CertUtil para la instalaci&oacute;n del certificado ra&iacute;z de  confianza en Firefox.
	 * @param certUtilAbsolutePath Directorio en el que se encuentra certutil.
	 * @param targetDir Directorio en el que se encuentra el certificado a importar.
	 * @param profilesDir Listado de directorios de perfiles de usuario de Mozilla Firefox.
	 * @throws IOException Cuando ocurre un error en el tratamiento de datos.
	 * @throws GeneralSecurityException Cuando ocurre un error en la inserci&oacute;n del certificado en el KeyStore.
	 */
	private static void executeCertUtilToImport(final String certUtilAbsolutePath,
			                                    final File targetDir,
			                                    final Set<File> profilesDir) throws IOException,
	                                                                                GeneralSecurityException {
		final File certutilFile = InstallCerFile(certUtilAbsolutePath);
		String certUtilExec = null;
		// Obtenemos todos los directorios de perfil de Firefox del usuario
		boolean error = false;

		// Exportamos el PATH y LD_LIBRARY_PATH en MACOSX
		if ( Platform.OS.LINUX.equals(Platform.getOS()) ) {
			certUtilExec = CERTUTIL_EXE;
		}
		else {
			certUtilExec = escapePath(certutilFile.getAbsolutePath());

			if ( Platform.OS.MACOSX.equals(Platform.getOS()) ) {
				
				RestoreConfigMacOSX.writeScriptFile(RestoreConfigMacOSX.mac_script_path, new StringBuilder(RestoreConfigMacOSX.EXPORT_PATH + certutilFile.getAbsolutePath().substring(0,certutilFile.getAbsolutePath().lastIndexOf(File.separator) )), true);
				RestoreConfigMacOSX.writeScriptFile(RestoreConfigMacOSX.mac_script_path, new StringBuilder(RestoreConfigMacOSX.EXPORT_LIBRARY_LD + certutilFile.getAbsolutePath().substring(0,certutilFile.getAbsolutePath().lastIndexOf(File.separator) )), true);
			}
		}
		for (final File profileDir : profilesDir) {
			if (!profileDir.isDirectory()) {
				continue;
			}

			final String[] certutilCommands = new String[] {
					certUtilExec,
					"-A", //$NON-NLS-1$
					"-d", //$NON-NLS-1$
					escapePath(profileDir.getAbsolutePath()),
					"-i", //$NON-NLS-1$
					escapePath(new File(targetDir, FILE_AUTOFIRMA_CERTIFICATE).getAbsolutePath()),
					"-n", //$NON-NLS-1$
					"\"" + RestoreConfigUtil.CERT_ALIAS + "\"", //$NON-NLS-1$ //$NON-NLS-2$
					"-t", //$NON-NLS-1$
					"\"C,,\"" //$NON-NLS-1$
			};

			error = execCommandLineCertUtil(certutilCommands, false);
			
		}

		if (error) {
			throw new KeyStoreException(
				"Error en la instalacion del certificado de CA en alguno de los perfiles de usuario " //$NON-NLS-1$
					+ "de Firefox. Es posible que la aplicacion funcione en su propio perfil. Si desea que la aplicacion se " //$NON-NLS-1$
					+ "ejecute correctamente en todos los perfiles, desinstalela y vuelvala a instalar." //$NON-NLS-1$
			);
		}

	}

	/** Ejecuta Mozilla CertUtil como comando del sistema.
	 * @param command Comando a ejecutar, con el nombre de comando y sus par&aacute;metros separados en un array.
	 * @return <code>true</code> si la ejecuci&oacute;n de CertUtil termin&oacute; con error, <code>false</code> si se
	 *         ejecut&oacute; correctamente.
	 * @throws IOException Si no se pudo realizar la propia ejecuci&oacute;n. */
	private static boolean execCommandLineCertUtil(final String[] command, final boolean chromeImport)
			throws IOException {
		
		Boolean error = Boolean.FALSE;
		final StringBuilder sb = new StringBuilder();
		
		for (final String s : command) {
			sb.append(s);
			sb.append(' ');
		}

		if (Platform.OS.MACOSX.equals(Platform.getOS())) {		
			RestoreConfigMacOSX.writeScriptFile(RestoreConfigMacOSX.mac_script_path, sb, true);
			return false;
		}
		else if (Platform.OS.LINUX.equals(Platform.getOS())) {
			// Ejecutamos el comando certutil en Linux
			final StringBuilder uninstall = new StringBuilder();
			String path = null;
			String uninstallPath = null;
			try {
				
				if(chromeImport) {
					//En Linux tambien se instala para todos los perfiles de
					// ususario del almacen de Chrome
					// tenemos en command[7] la ruta del fichero .crt, sacamos de
					// ahi la ruta del directorio de instalacion
					
					uninstall.append(command[0] + ' ');
					uninstall.append("-D" + ' '); //$NON-NLS-1$
					uninstall.append("-d" + ' ');//$NON-NLS-1$
					uninstall.append(command[2] + ' ');
					uninstall.append("-n" + ' ');//$NON-NLS-1$
					uninstall.append(command[5] + ' ');
										
					path = command[7].substring(0, command[7].lastIndexOf("/") + 1) + LINUX_SCRIPT_NAME; //$NON-NLS-1$
					uninstallPath = command[7].substring(0, command[7].lastIndexOf("/") + 1) + LINUX_UNINSTALLSCRIPT_NAME; //$NON-NLS-1$
					
				}
				else {
					
					// tenemos en command[5] la ruta del fichero .cer, sacamos de
					// ahi la ruta del directorio de instalacion
					
					uninstall.append(command[0] + ' ');
					uninstall.append("-D" + ' '); //$NON-NLS-1$
					uninstall.append("-d" + ' ');//$NON-NLS-1$
					uninstall.append(command[3] + ' ');
					uninstall.append("-n" + ' ');//$NON-NLS-1$
					uninstall.append(command[7] + ' ');
										
					path = command[5].substring(0, command[5].lastIndexOf("/") + 1) + LINUX_SCRIPT_NAME; //$NON-NLS-1$
					uninstallPath = command[5].substring(0, command[5].lastIndexOf("/") + 1) + LINUX_UNINSTALLSCRIPT_NAME; //$NON-NLS-1$
					
				}
				
				final File installScript = new File(path);
				final File uninstallScript = new File(uninstallPath);
							
				try (
						final FileOutputStream fout = new FileOutputStream(installScript, true);
						final FileOutputStream foutUninstall = new FileOutputStream(
								uninstallScript, true
								);
						) {
					fout.write(sb.toString().getBytes());
					foutUninstall.write(uninstall.toString().getBytes());
					
				}

				//Permisos de ejecucion del fichero de script certutil
//				Set<PosixFilePermission> perms = new HashSet<PosixFilePermission>();
//				perms.add(PosixFilePermission.OWNER_READ);
//				perms.add(PosixFilePermission.OWNER_WRITE);
//				perms.add(PosixFilePermission.OWNER_EXECUTE);
//								
//				Path FilePathObject = Paths.get(installScript.getAbsolutePath());
//				Files.setPosixFilePermissions(FilePathObject, perms);
				addExexPermissionsToFile(uninstallScript);
				addExexPermissionsToFile(installScript);
								
				// Primero desinstalamos las posibles versiones previas del certificado
				error = execCommand(new String[] {uninstallPath});
				error = execCommand(new String[] {path});
				
				if (!uninstallScript.delete()) {
					LOGGER.warning("No puedo eliminar el fichero de script: " + LINUX_UNINSTALLSCRIPT_NAME); //$NON-NLS-1$
				}
																				
				if (!installScript.delete()) {
					LOGGER.warning("No puedo eliminar el fichero de script: " + LINUX_SCRIPT_NAME); //$NON-NLS-1$
				}
				
				return error;
								
			}
			catch (final Exception e) {
				LOGGER.severe(
						"Excepcion en la ejecucion del script linux para la instalacion del certificado en el almacen de Firefox: " + e //$NON-NLS-1$
						);
				return true;
			}

		}
		else {
			LOGGER.info("Se ejecutara el siguiente comando:\n" + sb.toString()); //$NON-NLS-1$
			final Process process = new ProcessBuilder(command).start();
			// Cuando se instala correctamente no hay salida de ningun tipo, asi que se interpreta
			// cualquier salida como un error
			String line;
			try (
					final InputStream resIs = process.getInputStream();
					final BufferedReader resReader = new BoundedBufferedReader(
							new InputStreamReader(resIs),
							256, // Maximo 256 lineas de salida
							1024 // Maximo 1024 caracteres por linea
							);
					) {
				while ((line = resReader.readLine()) != null) {
					LOGGER.severe(line);
					return true;
				}
			}

			try (
					final InputStream errIs = process.getErrorStream();
					final BufferedReader errReader = new BoundedBufferedReader(
							new InputStreamReader(errIs),
							256, // Maximo 256 lineas de salida
							1024 // Maximo 1024 caracteres por linea
							);
					) {
				while ((line = errReader.readLine()) != null) {
					LOGGER.severe(line);
					return true;
				}
			}
		}

		return false;
	}


	private static void importCARootOnFirefoxKeyStore (final File appConfigDir,
			                                           final Set<File> profilesDir) {
		boolean installed = false;
		boolean cancelled = false;
		do {
			try {
				// Usamos CertUtil para instalar el certificado en Firefox.
				final String certutilExe = appConfigDir.getAbsolutePath() + File.separator + DIR_CERTUTIL + File.separator + CERTUTIL_EXE;
				
				executeCertUtilToImport(
					certutilExe,
					appConfigDir,
					profilesDir
				);
				installed = true;
						
			}
			catch (final Exception e) {
				LOGGER.warning(
						"No se pudo instalar la CA del certificado SSL para el socket en el almacen de Firefox: " + e //$NON-NLS-1$
						);
				final int result = JOptionPane.showConfirmDialog(
						null,
						SimpleAfirmaMessages.getString("RestoreConfigWindows.10"), //$NON-NLS-1$
						SimpleAfirmaMessages.getString("RestoreConfigWindows.1"), //$NON-NLS-1$
						JOptionPane.OK_CANCEL_OPTION,
						JOptionPane.WARNING_MESSAGE
						);
				if (result == JOptionPane.CANCEL_OPTION) {
					cancelled = true;
					LOGGER.severe(
							"El usuario cancelo la instalacion del certificado SSL para el socket en Firefox: " + e //$NON-NLS-1$
							);
				}
			}
		} while (!installed && !cancelled);

	}

	private static void importCARootOnFirefoxKeyStore(final File appConfigDir,
			                                          final File profilesDir) {

		// En Windows recibimos un unico directorio de perfil, lo convertimos a una estructura Set<File>
		final Set<File> profile = new HashSet<>(Arrays.asList(profilesDir.listFiles()));
		importCARootOnFirefoxKeyStore(appConfigDir, profile);
	}

	/** Ejecuta la aplicacion Mozilla CertUtil para eliminar el certificado de confianza ra&iacute;z
	 * SSL de Firefox.
	 * @param targetDir Directorio padre en el que se encuentra el directorio de certUtil.
	 * @throws IOException Cuando no se encuentra o puede leer alguno de los ficheros necesarios.
	 * @throws GeneralSecurityException Cuando no se puede ejecutar. */
	private static void executeCertUtilToDelete(final File targetDir) throws IOException, GeneralSecurityException {

		String certutilExe;
		// En linux se tiene certutil como dependencia
		if(!Platform.getOS().equals(Platform.OS.LINUX)) {
			final File certutilFile = new File(targetDir, DIR_CERTUTIL + File.separator + CERTUTIL_EXE);

			if (!certutilFile.exists() || !certutilFile.isFile() || !certutilFile.canExecute()) {
				throw new IOException("No se encuentra o no se puede leer el ejecutable para la instalacion en Firefox"); //$NON-NLS-1$
			}
			certutilExe = certutilFile.getAbsolutePath();
		}
		else {
			certutilExe = CERTUTIL_EXE;
		}

		//Se obtienen todos los usuarios para los que se va a desinstalar el certificado en Firefox
		final File usersBaseDir = new File(USERS_WINDOWS_PATH);
		final String[] userDirNames = usersBaseDir.list(new FilenameFilter() {
		  @Override
		  public boolean accept(final File current, final String name) {
		    return new File(current, name).isDirectory();
		  }
		});

		//Para Windows XP la ruta de los perfiles de Firefox y de los usuarios es diferente
		if(System.getProperty("os.name") != null && System.getProperty("os.name").contains("XP")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			WINDOWS_MOZILLA_PATH = "\\Application Data\\Mozilla\\Firefox\\profiles.ini"; //$NON-NLS-1$
			USERS_WINDOWS_PATH = "C:\\Documents and Settings\\"; //$NON-NLS-1$
		}
		// Obtenemos todos los directorios de perfil de Firefox del usuario

		boolean error = false;

		for(final String userDirName : userDirNames) {
			
			// Nos saltamos siempre el usuario por defecto del sistema para
			// evitar corromperlo
			if (DEFAULT_WINDOWS_USER_NAME.equalsIgnoreCase(userDirName)) {
				continue;
			}
			
			LOGGER.info("Se comprueba la existencia del perfil de Firefox: " + USERS_WINDOWS_PATH + userDirName + WINDOWS_MOZILLA_PATH); //$NON-NLS-1$
			if(new File(USERS_WINDOWS_PATH + userDirName + WINDOWS_MOZILLA_PATH).exists()) {
				final File profilesDir = new File(
						MozillaKeyStoreUtilities.getMozillaUserProfileDirectoryWindows(
								USERS_WINDOWS_PATH + userDirName + WINDOWS_MOZILLA_PATH
								)
						).getParentFile();
				for (final File profileDir : profilesDir.listFiles()) {
					if (!profileDir.isDirectory()) {
						continue;
					}

					final String[] certutilCommands = new String[] {
							"\"" + certutilExe + "\"", //$NON-NLS-1$ //$NON-NLS-2$
							"-D", //$NON-NLS-1$
							"-d", //$NON-NLS-1$
							"\"" + profileDir.getAbsolutePath() + "\"", //$NON-NLS-1$ //$NON-NLS-2$
							"-n", //$NON-NLS-1$
							"\"" + RestoreConfigUtil.CERT_ALIAS + "\"", //$NON-NLS-1$ //$NON-NLS-2$
					};

					final Process process = new ProcessBuilder(certutilCommands).start();

					LOGGER.info("Comando certutil ejecutado: " + Arrays.toString(certutilCommands)); //$NON-NLS-1$
					// Cuando se instala correctamente no hay salida de ningun tipo, asi que se interpreta
					// cualquier salida como un error
					String line;
					try (
							final InputStream resIs = process.getInputStream();
							final BufferedReader resReader = new BoundedBufferedReader(
									new InputStreamReader(resIs),
									256, // Maximo 256 lineas de salida
									1024 // Maximo 1024 caracteres por linea
									);
							) {
						while ((line = resReader.readLine()) != null) {
							error = true;
							LOGGER.severe(line);
						}
					}

					try (
							final InputStream errIs = process.getErrorStream();
							final BufferedReader errReader = new BoundedBufferedReader(
									new InputStreamReader(errIs),
									256, // Maximo 256 lineas de salida
									1024 // Maximo 1024 caracteres por linea
									);
							) {
						while ((line = errReader.readLine()) != null) {
							error = true;
							LOGGER.severe(line);
						}
					}
				}
			}
		}

		if (error) {
			throw new KeyStoreException("Error en el borrado del certificado de CA en alguno de los perfiles de usuario de Firefox"); //$NON-NLS-1$
		}
	}

	private static void deleteConfigDir(final File appConfigDir) {
		RestoreConfigUtil.deleteDir(new File(appConfigDir, DIR_CERTUTIL));
	
	}

	static void copyConfigurationFiles(final File appConfigDir) throws IOException {
		final File certutil = new File(appConfigDir, DIR_CERTUTIL);
		if (!certutil.exists()) {
			uncompressResource(RESOURCE_BASE + FILE_CERTUTIL, appConfigDir);
			if (Platform.OS.MACOSX.equals(Platform.getOS())) {
				RestoreConfigMacOSX.addExexPermissionsToAllFilesOnDirectory(certutil);
			}
		}
	}

	/** Descomprime un fichero ZIP de recurso al disco.
	 * @param resource Ruta del recurso ZIP.
	 * @param outDir Directorio local en el que descomprimir.
	 * @throws IOException Cuando ocurre un error al descomprimir.
	 **/
	private static void uncompressResource(final String resource, final File outDir) throws IOException {
		int n;
		ZipEntry entry;
		final byte[] buffer = new byte[1024];
		try (final ZipInputStream zipIs = new ZipInputStream(
				RestoreConfigFirefox.class.getResourceAsStream(resource));) {
			// en linux el funcionamiento es ligeramente diferente
			if (Platform.OS.LINUX == Platform.getOS()) {
				while ((entry = zipIs.getNextEntry()) != null) {
					new File(outDir, "certutil").mkdirs(); //$NON-NLS-1$
					try (
							final FileOutputStream outFis = new FileOutputStream(
									new File(
											outDir,
											entry.getName()
											)
									);
							) {
						while ((n = zipIs.read(buffer)) > 0) {
							outFis.write(buffer, 0, n);
						}
						outFis.flush();
					}

					zipIs.closeEntry();
				}
			}
			else {
				while ((entry = zipIs.getNextEntry()) != null) {
					if (entry.isDirectory()) {
						new File(outDir, entry.getName()).mkdirs();
					}
					else {
						try (
								final FileOutputStream outFis = new FileOutputStream(
										new File(
												outDir,
												entry.getName()
												)
										);
								) {
							while ((n = zipIs.read(buffer)) > 0) {
								outFis.write(buffer, 0, n);
							}
							outFis.flush();
						}
					}
					zipIs.closeEntry();
				}
			}

		}
	}

	private static ArrayList<File> getFirefoxProfilesDir() {
		final ArrayList<File> fileList = new ArrayList<>();

		//Para Windows XP la ruta de los perfiles de Firefox y de los usuarios es diferente
		LOGGER.info("Version de Windows detectada: " + System.getProperty("os.name")); //$NON-NLS-1$ //$NON-NLS-2$
		if(System.getProperty("os.name") != null && System.getProperty("os.name").contains("XP")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			WINDOWS_MOZILLA_PATH = "\\Application Data\\Mozilla\\Firefox\\profiles.ini"; //$NON-NLS-1$
			USERS_WINDOWS_PATH = "C:\\Documents and Settings\\"; //$NON-NLS-1$
		}

		// Se obtienen todos los usuarios para los que se va a instalar el
		// certificado en Firefox
		final File usersBaseDir = new File(USERS_WINDOWS_PATH);
		final String[] userDirNames = usersBaseDir.list(new FilenameFilter() {
			@Override
			public boolean accept(final File current, final String name) {
				return new File(current, name).isDirectory();
			}
		});

		try {
			for(final String userDirName : userDirNames) {
				// Nos saltamos siempre el usuario por defecto del sistema para evitar corromperlo
				if (DEFAULT_WINDOWS_USER_NAME.equalsIgnoreCase(userDirName)) {
					continue;
				}
				
				if(new File(USERS_WINDOWS_PATH + userDirName + WINDOWS_MOZILLA_PATH).exists()) {
					fileList.add(
							new File(
									MozillaKeyStoreUtilities.getMozillaUserProfileDirectoryWindows(
											USERS_WINDOWS_PATH + userDirName + WINDOWS_MOZILLA_PATH)
							).getParentFile());
					LOGGER.info("Se usa el perfil de Firefox: " + USERS_WINDOWS_PATH + userDirName + WINDOWS_MOZILLA_PATH); //$NON-NLS-1$
				}
			}
		}
		catch (final Exception e) {
			LOGGER.warning("No se encontro el directorio de perfiles de Mozilla Firefox: " + e); //$NON-NLS-1$
		}
		return fileList;
	}

	/** Devuelve un listado con todos directorios personales de los usuarios del sistema ejecutando un script.
	 * Utilizado en Linux y MacOSX
	 * @param command Script para sacar los directorios de usuario dentro del sistema operativo.
	 * <ul>
	 *  <li>En Linux contiene el contenido del script a ejecutar.</li>
	 *  <li>En OS X contiene la ruta del script a ejecutar.</li>
	 * </ul>
	 * @return Listado con todos directorios personales de los usuarios del sistema. */
	private static List<String> getSystemUsersHomes(final String[] command) {
		if (Platform.OS.LINUX.equals(Platform.getOS())) {
			try {
				final Process process = new ProcessBuilder(command).start();

				String line;
				// arraylist con todos los directorios de usuario
				final List<String> usersDir = new ArrayList<>();
				try (
						final InputStream resIs = process.getInputStream();
						final BufferedReader resReader = new BoundedBufferedReader(
								new InputStreamReader(resIs),
								2048, // Maximo 256 lineas de salida (256 perfiles)
								2048 // Maximo 2048 caracteres por linea
								);
						) {
					while ((line = resReader.readLine()) != null) {
						usersDir.add(line);
					}
				}

				return usersDir;
			}
			catch (final Exception e) {
				LOGGER.info("Error al generar el listado de directorios de usuarios del sistema." + e); //$NON-NLS-1$
				return null;
			}
		}
		// MAC
		else if (Platform.OS.MACOSX.equals(Platform.getOS())) {
			try {
				final Object o = RestoreConfigMacOSX.executeScript(command[0],false,false);
				final List<String> usersDir = new ArrayList<>();
				String line;
				final String initLine = "dir: "; //$NON-NLS-1$
				try (
						final InputStream resIs = new ByteArrayInputStream(o.toString().getBytes());
						final BufferedReader resReader = new BoundedBufferedReader(
								new InputStreamReader(resIs),
								2048, // Maximo 2048 lineas de salida (256 perfiles)
								2048 // Maximo 2048 caracteres por linea
								);
						) {
					while ((line = resReader.readLine()) != null) {
						if (line.startsWith(initLine)){
							usersDir.add(
									line.substring(
											line.indexOf(initLine) + initLine.length()
											)
									);
						}
					}
				}

				return usersDir;

			}
			catch (final IOException e) {
				LOGGER.info("Error al generar el listado perfiles de Firefox del sistema: " + e); //$NON-NLS-1$
				return null;
			}
		}
		else {
			throw new IllegalArgumentException("Sistema operativo no soportado: " + Platform.getOS()); //$NON-NLS-1$
		}

	}

	/** Devuelve un listado con los directorios donde se encuentra el fichero <i>profiles.ini</i> de firefox en Linux y en OS X.
	 * @param users Listado de usuarios del sistema.
	 * @return Listado de directorios donde se encuentra el fichero <i>profiles.ini</i>. */
	private static List<File> getMozillaUsersProfilesPath(final List<String> users){
		String pathProfile = null;
		final List<File> path = new ArrayList<>();
		if (Platform.OS.LINUX.equals(Platform.getOS())) {
			pathProfile = LINUX_MOZILLA_PATH;
		}
		else if (Platform.OS.MACOSX.equals(Platform.getOS())) {
			pathProfile = MACOSX_MOZILLA_PATH;
		}
		else {
			throw new IllegalArgumentException("Sistema operativo no soportado: " + Platform.getOS()); //$NON-NLS-1$
		}
		for (final String usr: users){
			final File mozillaPath = new File(usr + pathProfile);
			// comprobamos que el fichero exista
			if (mozillaPath.exists() && mozillaPath.isFile()){
				path.add(mozillaPath);
				LOGGER.info("Ruta: " + mozillaPath ); //$NON-NLS-1$
			}
		}
		return path;
	}

	/** Devuelve un listado de directorios donde se encuentran los perfiles de usuario de firefox en Linux.
	 * @param profilesPath Listado de directorios que contienen un fichero <i>profiles.ini</i>.
	 * @return Listado de directorios donde se encuentran los perfiles de usuario de Firefox. */
	private static Set<File> getProfiles(final List<File> profilesPath){
		final String PATH = "Path="; //$NON-NLS-1$
		final Set<File> profile = new HashSet<>();
		for (final File path: profilesPath){
			String line;
			try (
				final InputStream resIs = new FileInputStream(path);
				final BufferedReader resReader = new BoundedBufferedReader(
					new InputStreamReader(resIs),
					256, // Maximo 256 lineas de salida (256 perfiles por "profiles.ini")
					2048 // Maximo 2048 caracteres por linea
				);
			) {
				while ((line = resReader.readLine()) != null) {
					if (line.startsWith(PATH)){
						final File file = new File(
							path.getAbsolutePath().substring(
								0, path.getAbsolutePath().lastIndexOf("/") + 1) + line.substring(PATH.length() //$NON-NLS-1$
							)
						);
						if (file.exists() && file.isDirectory()){
							profile.add(file);
						}
					}
				}
			}
			catch (final Exception e) {
				LOGGER.severe("Error al buscar los directorios de perfiles de Firefox: " + e); //$NON-NLS-1$
			}
		}
		return profile;
	}

		
	/**
	 * Ejecuta un comando de consola
	 * @param commands Nombre del comando y argumentos
	 * @return {@code true} si la ejecuci&oacute;n devolvioacute; alg&uacute;n error {@code false} en caso contrario
	 */
	private static Boolean execCommand(final String[] command) throws IOException {
				
		Boolean error = Boolean.FALSE;
		
		LOGGER.info("Se ejecutara el siguiente comando:\n" + Arrays.toString(command)); //$NON-NLS-1$
		final Process process = new ProcessBuilder(command).start();
		// Cuando se instala correctamente no hay salida de ningun tipo, asi que se interpreta
		// cualquier salida como un error
		String line;
		try (
				final InputStream resIs = process.getInputStream();
				final BufferedReader resReader = new BoundedBufferedReader(
						new InputStreamReader(resIs),
						256, // Maximo 256 lineas de salida
						1024 // Maximo 1024 caracteres por linea
						);
				) {
			while ((line = resReader.readLine()) != null) {
				LOGGER.severe(line);
				return true;
			}
		}

		try (
				final InputStream errIs = process.getErrorStream();
				final BufferedReader errReader = new BoundedBufferedReader(
						new InputStreamReader(errIs),
						256, // Maximo 256 lineas de salida
						1024 // Maximo 1024 caracteres por linea
						);
				) {
			while ((line = errReader.readLine()) != null) {
				LOGGER.severe(line);
				return true;
			}
		}
								
		return error;
	}
}
