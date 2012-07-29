/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2004-08 Ben Fry and Casey Reas
  Copyright (c) 2001-04 Massachusetts Institute of Technology

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.app.debug;

import processing.app.Base;
import processing.app.Preferences;
import processing.app.Sketch;
import processing.app.SketchCode;
import processing.core.*;
import processing.app.I18n;
import static processing.app.I18n._;

import java.io.*;
import java.util.*;
import java.util.zip.*;


public class Compiler implements MessageConsumer {
  static final String BUGS_URL =
    _("http://code.google.com/p/arduino/issues/list");
  static final String SUPER_BADNESS =
    I18n.format(_("Compiler error, please submit this code to {0}"), BUGS_URL);

  Sketch sketch;
  String buildPath;
  String primaryClassName;
  boolean verbose;

  RunnerException exception;

  public Compiler() { }

  /**
   * Compile with avr-gcc.
   *
   * @param sketch Sketch object to be compiled.
   * @param buildPath Where the temporary files live and will be built from.
   * @param primaryClassName the name of the combined sketch file w/ extension
   * @return true if successful.
   * @throws RunnerException Only if there's a problem. Only then.
   */
  public boolean compile(Sketch sketch,
                         String buildPath,
                         String primaryClassName,
                         boolean verbose) throws RunnerException {
    this.sketch = sketch;
    this.buildPath = buildPath;
    this.primaryClassName = primaryClassName;
    this.verbose = verbose;

    // the pms object isn't used for anything but storage
    MessageStream pms = new MessageStream(this);


    String basePath = Base.getBasePath();
    Map<String, String> boardPreferences = Base.getBoardPreferences();
    String core = boardPreferences.get("build.core");
    if (core == null) {
    	RunnerException re = new RunnerException(_("No board selected; please choose a board from the Tools > Board menu."));
      re.hideStackTrace();
      throw re;
    }
    String corePath;
    
    if (core.indexOf(':') == -1) {
      Target t = Base.getTarget();
      File coreFolder = new File(new File(t.getFolder(), "cores"), core);
      corePath = coreFolder.getAbsolutePath();
    } else {
      Target t = Base.targetsTable.get(core.substring(0, core.indexOf(':')));
      File coreFolder = new File(t.getFolder(), "cores");
      coreFolder = new File(coreFolder, core.substring(core.indexOf(':') + 1));
      corePath = coreFolder.getAbsolutePath();
    }

    String variant = boardPreferences.get("build.variant");
    String variantPath = null;
    
    if (variant != null) {
      if (variant.indexOf(':') == -1) {
	Target t = Base.getTarget();
	File variantFolder = new File(new File(t.getFolder(), "variants"), variant);
	variantPath = variantFolder.getAbsolutePath();
      } else {
	Target t = Base.targetsTable.get(variant.substring(0, variant.indexOf(':')));
	File variantFolder = new File(t.getFolder(), "variants");
	variantFolder = new File(variantFolder, variant.substring(variant.indexOf(':') + 1));
	variantPath = variantFolder.getAbsolutePath();
      }
    }

    List<File> objectFiles = new ArrayList<File>();

   // 0. include paths for core + all libraries

   sketch.setCompilingProgress(20);
   List includePaths = new ArrayList();
   includePaths.add(corePath);
   if (variantPath != null) includePaths.add(variantPath);
   for (File file : sketch.getImportedLibraries()) {
     includePaths.add(file.getPath());
   }

   // 1. compile the sketch (already in the buildPath)

   sketch.setCompilingProgress(30);
   objectFiles.addAll(
     compileFiles(basePath, buildPath, includePaths,
               findFilesInPath(buildPath, "S", false),
               findFilesInPath(buildPath, "c", false),
               findFilesInPath(buildPath, "cpp", false),
               boardPreferences));

   // 2. compile the libraries, outputting .o files to: <buildPath>/<library>/

   sketch.setCompilingProgress(40);
   for (File libraryFolder : sketch.getImportedLibraries()) {
     File outputFolder = new File(buildPath, libraryFolder.getName());
     File utilityFolder = new File(libraryFolder, "utility");
     createFolder(outputFolder);
     // this library can use includes in its utility/ folder
     includePaths.add(utilityFolder.getAbsolutePath());
     objectFiles.addAll(
       compileFiles(basePath, outputFolder.getAbsolutePath(), includePaths,
               findFilesInFolder(libraryFolder, "S", false),
               findFilesInFolder(libraryFolder, "c", false),
               findFilesInFolder(libraryFolder, "cpp", false),
               boardPreferences));
     outputFolder = new File(outputFolder, "utility");
     createFolder(outputFolder);
     objectFiles.addAll(
       compileFiles(basePath, outputFolder.getAbsolutePath(), includePaths,
               findFilesInFolder(utilityFolder, "S", false),
               findFilesInFolder(utilityFolder, "c", false),
               findFilesInFolder(utilityFolder, "cpp", false),
               boardPreferences));
     // other libraries should not see this library's utility/ folder
     includePaths.remove(includePaths.size() - 1);
   }

   // 3. compile the core, outputting .o files to <buildPath> and then
   // collecting them into the core.a library file.

   sketch.setCompilingProgress(50);
  includePaths.clear();
  includePaths.add(corePath);  // include path for core only
  if (variantPath != null) includePaths.add(variantPath);
  List<File> coreObjectFiles =
    compileFiles(basePath, buildPath, includePaths,
              findFilesInPath(corePath, "S", true),
              findFilesInPath(corePath, "c", true),
              findFilesInPath(corePath, "cpp", true),
              boardPreferences);

  String arch = Base.getArch();
  String runtimeLibraryName = buildPath + File.separator + "core.a";
  List baseCommandAR = new ArrayList();
  if(arch == "msp430") { 
      baseCommandAR.add(BasePath + "msp430-ar");
    } else if (arch == "arduino") {
      baseCommandAR.add(BasePath + "avr-ar");
    } else {
      baseCommandAR.add(BasePath + "ar");
    }
  }

  baseCommandAR.add("rcs");
  baseCommandAR.add(runtimeLibraryName);

  for(File file : coreObjectFiles) {
     List commandAR = new ArrayList(baseCommandAR);
     commandAR.add(file.getAbsolutePath());
     execAsynchronously(commandAR);
   }

    // 4. link it all together into the .elf file
    // For atmega2560, need --relax linker option to link larger
    // programs correctly.
    String optRelax = "";
    String atmega2560 = new String ("atmega2560");
    if ( atmega2560.equals(boardPreferences.get("build.mcu")) ) {
        optRelax = new String(",--relax");
    }
    sketch.setCompilingProgress(60);
    List baseCommandLinker = new ArrayList();
    if (arch == "msp430") { 
      baseCommandLinker.add(basePath + "msp430-gcc");
      baseCommandLinker.add("-Os");
        // msp430 linker has an issue with main residing in an archive, cora.a in this case.
        // -u,main works around this by forcing the linker to find a definition for main.
      baseCommandLinker.add("-Wl,-gc-sections,-u,main");
      baseCommandLinker.add("-mmcu=" + boardPreferences.get("build.mcu"));
      baseCommandLinker.add("-o");
    } else if (arch == "arduino") {
      baseCommandLinker.add(basePath + "avr-gcc",
      baseCommandLinker.add("-Os",
      baseCommandLinker.add("-Wl,--gc-sections"+optRelax,
      baseCommandLinker.add("-mmcu=" + boardPreferences.get("build.mcu"),
      baseCommandLinker.add("-o",
    } else {
      baseCommandLinker.add(BasePath + "g++");
      baseCommandLinker.add("-o");
    }

    baseCommandLinker.add(buildPath + File.separator + primaryClassName + ".elf");

    for (File file : objectFiles) {
      baseCommandLinker.add(file.getAbsolutePath());
    }

    baseCommandLinker.add(runtimeLibraryName);
    baseCommandLinker.add("-L" + buildPath);
    baseCommandLinker.add("-lm");

    execAsynchronously(baseCommandLinker);

    List baseCommandObjcopy;
    if (arch == "msp430") { 
    baseCommandObjcopy = new ArrayList(Arrays.asList(new String[] {
      basePath + "msp430-objcopy",
      "-O",
      "-R",
    }));
    } else if (arch == "arduino") {
      baseCommandObjcopy = new ArrayList(Arrays.asList(new String[] {
        basePath + "avr-objcopy",
        "-O",
        "-R",
      }));

    }
    List commandObjcopy;
    if (arch == "msp430") {
      //nothing 
    } else if (arch == "arduino") {
        // 5. extract EEPROM data (from EEMEM directive) to .eep file.
      sketch.setCompilingProgress(70);
      commandObjcopy = new ArrayList(baseCommandObjcopy);
      commandObjcopy.add(2, "ihex");
      commandObjcopy.set(3, "-j");
      commandObjcopy.add(".eeprom");
      commandObjcopy.add("--set-section-flags=.eeprom=alloc,load");
      commandObjcopy.add("--no-change-warnings");
      commandObjcopy.add("--change-section-lma");
      commandObjcopy.add(".eeprom=0");
      commandObjcopy.add(buildPath + File.separator + primaryClassName + ".elf");
      commandObjcopy.add(buildPath + File.separator + primaryClassName + ".eep");
      execAsynchronously(commandObjcopy);
    }
    
    if ((arch == "msp430") || (arch == "arduino")) {
      // 6. build the .hex file
      sketch.setCompilingProgress(80);
      commandObjcopy = new ArrayList(baseCommandObjcopy);
      commandObjcopy.add(2, "ihex");
      commandObjcopy.add(".eeprom"); // remove eeprom data
      commandObjcopy.add(buildPath + File.separator + primaryClassName + ".elf");
      commandObjcopy.add(buildPath + File.separator + primaryClassName + ".hex");
      execAsynchronously(commandObjcopy);
    }

    sketch.setCompilingProgress(90);

    return true;
  }


  private List<File> compileFiles(String basePath,
                                  String buildPath, List<File> includePaths,
                                  List<File> sSources, 
                                  List<File> cSources, List<File> cppSources,
                                  Map<String, String> boardPreferences)
    throws RunnerException {

    List<File> objectPaths = new ArrayList<File>();
    
    for (File file : sSources) {
      String objectPath = buildPath + File.separator + file.getName() + ".o";
      objectPaths.add(new File(objectPath));
      execAsynchronously(getCommandCompilerS(basePath, includePaths,
                                             file.getAbsolutePath(),
                                             objectPath,
                                             boardPreferences));
    }
 		
    for (File file : cSources) {
        String objectPath = buildPath + File.separator + file.getName() + ".o";
        String dependPath = buildPath + File.separator + file.getName() + ".d";
        File objectFile = new File(objectPath);
        File dependFile = new File(dependPath);
        objectPaths.add(objectFile);
        if (is_already_compiled(file, objectFile, dependFile, boardPreferences)) continue;
        execAsynchronously(getCommandCompilerC(basePath, includePaths,
                                               file.getAbsolutePath(),
                                               objectPath,
                                               boardPreferences));
    }

    for (File file : cppSources) {
        String objectPath = buildPath + File.separator + file.getName() + ".o";
        String dependPath = buildPath + File.separator + file.getName() + ".d";
        File objectFile = new File(objectPath);
        File dependFile = new File(dependPath);
        objectPaths.add(objectFile);
        if (is_already_compiled(file, objectFile, dependFile, boardPreferences)) continue;
        execAsynchronously(getCommandCompilerCPP(basePath, includePaths,
                                                 file.getAbsolutePath(),
                                                 objectPath,
                                                 boardPreferences));
    }
    
    return objectPaths;
  }

  private boolean is_already_compiled(File src, File obj, File dep, Map<String, String> prefs) {
    boolean ret=true;
    try {
      //System.out.println("\n  is_already_compiled: begin checks: " + obj.getPath());
      if (!obj.exists()) return false;  // object file (.o) does not exist
      if (!dep.exists()) return false;  // dep file (.d) does not exist
      long src_modified = src.lastModified();
      long obj_modified = obj.lastModified();
      if (src_modified >= obj_modified) return false;  // source modified since object compiled
      if (src_modified >= dep.lastModified()) return false;  // src modified since dep compiled
      BufferedReader reader = new BufferedReader(new FileReader(dep.getPath()));
      String line;
      boolean need_obj_parse = true;
      while ((line = reader.readLine()) != null) {
        if (line.endsWith("\\")) {
          line = line.substring(0, line.length() - 1);
        }
        line = line.trim();
        if (line.length() == 0) continue; // ignore blank lines
        if (need_obj_parse) {
          // line is supposed to be the object file - make sure it really is!
          if (line.endsWith(":")) {
            line = line.substring(0, line.length() - 1);
            String objpath = obj.getCanonicalPath();
            File linefile = new File(line);
            String linepath = linefile.getCanonicalPath();
            //System.out.println("  is_already_compiled: obj =  " + objpath);
            //System.out.println("  is_already_compiled: line = " + linepath);
            if (objpath.compareTo(linepath) == 0) {
              need_obj_parse = false;
              continue;
            } else {
              ret = false;  // object named inside .d file is not the correct file!
              break;
            }
          } else {
            ret = false;  // object file supposed to end with ':', but didn't
            break;
          }
        } else {
          // line is a prerequisite file
          File prereq = new File(line);
          if (!prereq.exists()) {
            ret = false;  // prerequisite file did not exist
            break;
          }
          if (prereq.lastModified() >= obj_modified) {
            ret = false;  // prerequisite modified since object was compiled
            break;
          }
          //System.out.println("  is_already_compiled:  prerequisite ok");
        }
      }
      reader.close();
    } catch (Exception e) {
      return false;  // any error reading dep file = recompile it
    }
    if (ret && (verbose || Preferences.getBoolean("build.verbose"))) {
      System.out.println("  Using previously compiled: " + obj.getPath());
    }
    return ret;
  }

  boolean firstErrorFound;
  boolean secondErrorFound;

  /**
   * Either succeeds or throws a RunnerException fit for public consumption.
   */
  private void execAsynchronously(List commandList) throws RunnerException {
    String[] command = new String[commandList.size()];
    commandList.toArray(command);
    int result = 0;
    
    if (verbose || Preferences.getBoolean("build.verbose")) {
      for(int j = 0; j < command.length; j++) {
        System.out.print(command[j] + " ");
      }
      System.out.println();
    }

    firstErrorFound = false;  // haven't found any errors yet
    secondErrorFound = false;

    Process process;
    
    try {
      process = Runtime.getRuntime().exec(command);
    } catch (IOException e) {
      RunnerException re = new RunnerException(e.getMessage());
      re.hideStackTrace();
      throw re;
    }

    MessageSiphon in = new MessageSiphon(process.getInputStream(), this);
    MessageSiphon err = new MessageSiphon(process.getErrorStream(), this);

    // wait for the process to finish.  if interrupted
    // before waitFor returns, continue waiting
    boolean compiling = true;
    while (compiling) {
      try {
        if (in.thread != null)
          in.thread.join();
        if (err.thread != null)
          err.thread.join();
        result = process.waitFor();
        //System.out.println("result is " + result);
        compiling = false;
      } catch (InterruptedException ignored) { }
    }

    // an error was queued up by message(), barf this back to compile(),
    // which will barf it back to Editor. if you're having trouble
    // discerning the imagery, consider how cows regurgitate their food
    // to digest it, and the fact that they have five stomaches.
    //
    //System.out.println("throwing up " + exception);
    if (exception != null) { throw exception; }

    if (result > 1) {
      // a failure in the tool (e.g. unable to locate a sub-executable)
      System.err.println(
	  I18n.format(_("{0} returned {1}"), command[0], result));
    }

    if (result != 0) {
      RunnerException re = new RunnerException(_("Error compiling."));
      re.hideStackTrace();
      throw re;
    }
  }


  /**
   * Part of the MessageConsumer interface, this is called
   * whenever a piece (usually a line) of error message is spewed
   * out from the compiler. The errors are parsed for their contents
   * and line number, which is then reported back to Editor.
   */
  public void message(String s) {
    int i;

    // remove the build path so people only see the filename
    // can't use replaceAll() because the path may have characters in it which
    // have meaning in a regular expression.
    if (!verbose) {
      while ((i = s.indexOf(buildPath + File.separator)) != -1) {
        s = s.substring(0, i) + s.substring(i + (buildPath + File.separator).length());
      }
    }
  
    // look for error line, which contains file name, line number,
    // and at least the first line of the error message
    String errorFormat = "([\\w\\d_]+.\\w+):(\\d+):\\s*error:\\s*(.*)\\s*";
    String[] pieces = PApplet.match(s, errorFormat);

//    if (pieces != null && exception == null) {
//      exception = sketch.placeException(pieces[3], pieces[1], PApplet.parseInt(pieces[2]) - 1);
//      if (exception != null) exception.hideStackTrace();
//    }
    
    if (pieces != null) {
      String error = pieces[3], msg = "";
      
      if (pieces[3].trim().equals("SPI.h: No such file or directory")) {
        error = _("Please import the SPI library from the Sketch > Import Library menu.");
        msg = _("\nAs of Arduino 0019, the Ethernet library depends on the SPI library." +
              "\nYou appear to be using it or another library that depends on the SPI library.\n\n");
      }
      
      if (pieces[3].trim().equals("'BYTE' was not declared in this scope")) {
        error = _("The 'BYTE' keyword is no longer supported.");
        msg = _("\nAs of Arduino 1.0, the 'BYTE' keyword is no longer supported." +
              "\nPlease use Serial.write() instead.\n\n");
      }
      
      if (pieces[3].trim().equals("no matching function for call to 'Server::Server(int)'")) {
        error = _("The Server class has been renamed EthernetServer.");
        msg = _("\nAs of Arduino 1.0, the Server class in the Ethernet library " +
              "has been renamed to EthernetServer.\n\n");
      }
      
      if (pieces[3].trim().equals("no matching function for call to 'Client::Client(byte [4], int)'")) {
        error = _("The Client class has been renamed EthernetClient.");
        msg = _("\nAs of Arduino 1.0, the Client class in the Ethernet library " +
              "has been renamed to EthernetClient.\n\n");
      }
      
      if (pieces[3].trim().equals("'Udp' was not declared in this scope")) {
        error = _("The Udp class has been renamed EthernetUdp.");
        msg = _("\nAs of Arduino 1.0, the Udp class in the Ethernet library " +
              "has been renamed to EthernetClient.\n\n");
      }
      
      if (pieces[3].trim().equals("'class TwoWire' has no member named 'send'")) {
        error = _("Wire.send() has been renamed Wire.write().");
        msg = _("\nAs of Arduino 1.0, the Wire.send() function was renamed " +
              "to Wire.write() for consistency with other libraries.\n\n");
      }
      
      if (pieces[3].trim().equals("'class TwoWire' has no member named 'receive'")) {
        error = _("Wire.receive() has been renamed Wire.read().");
        msg = _("\nAs of Arduino 1.0, the Wire.receive() function was renamed " +
              "to Wire.read() for consistency with other libraries.\n\n");
      }

      RunnerException e = sketch.placeException(error, pieces[1], PApplet.parseInt(pieces[2]) - 1);

      // replace full file path with the name of the sketch tab (unless we're
      // in verbose mode, in which case don't modify the compiler output)
      if (e != null && !verbose) {
        SketchCode code = sketch.getCode(e.getCodeIndex());
        String fileName = code.isExtension(sketch.getDefaultExtension()) ? code.getPrettyName() : code.getFileName();
        s = fileName + ":" + e.getCodeLine() + ": error: " + pieces[3] + msg;        
      }
            
      if (exception == null && e != null) {
        exception = e;
        exception.hideStackTrace();
      }      
    }
    
    System.err.print(s);
  }

  /////////////////////////////////////////////////////////////////////////////

  static private List getCommandCompilerS(String basePath, List includePaths,
    String sourceName, String objectName, Map<String, String> boardPreferences) {
    String arch = Base.getArch();
    
    List baseCommandCompiler = new ArrayList();
    
    if (arch == "msp430") {
    	//as per
    	//http://mspgcc.sourceforge.net/manual/x1522.html
	baseCommandCompiler.add(basePath + "msp430-gcc");
	baseCommandCompiler.add("-c"); // compile, don't link
	baseCommandCompiler.add("-g"); // include debugging info (so errors include line numbers)
	baseCommandCompiler.add("-mmcu=" + boardPreferences.get("build.mcu"));
	baseCommandCompiler.add("-DF_CPU=" + boardPreferences.get("build.f_cpu"));
	baseCommandCompiler.add("-DARDUINO=" + Base.REVISION);
	baseCommandCompiler.add("-DENERGIA=" + Base.EREVISION);
    } else if (arch == "arduino") {
	baseCommandCompiler = new ArrayList(Arrays.asList(new String[] {
	baseCommandCompiler.add(basePath + "avr-gcc",
	baseCommandCompiler.add("-c", // compile, don't link
	baseCommandCompiler.add("-g", // include debugging info (so errors include line numbers)
	baseCommandCompiler.add("-assembler-with-cpp",
	baseCommandCompiler.add("-mmcu=" + boardPreferences.get("build.mcu"),
	baseCommandCompiler.add("-DF_CPU=" + boardPreferences.get("build.f_cpu"),
	baseCommandCompiler.add("-DARDUINO=" + Base.REVISION,
    } else {
	baseCommandCompiler.add(avrBasePath + "g++");
	baseCommandCompiler.add("-c"); // compile, don't link
    }

    for (int i = 0; i < includePaths.size(); i++) {
      baseCommandCompiler.add("-I" + (String) includePaths.get(i));
    }

    baseCommandCompiler.add(sourceName);
    baseCommandCompiler.add("-o"+ objectName);

    return baseCommandCompiler;
  }

  
  static private List getCommandCompilerC(String basePath, List includePaths,
    String sourceName, String objectName, Map<String, String> boardPreferences) {
	 String arch = Base.getArch();
     List baseCommandCompiler = new ArrayList(); ;

      if (arch == "msp430") {
	baseCommandCompiler(basePath + "msp430-gcc");
	baseCommandCompiler("-c"); // compile, don't link
	baseCommandCompiler("-g"); // include debugging info (so errors include line numbers)
	baseCommandCompiler("-Os"); // optimize for size
	baseCommandCompiler(Preferences.getBoolean("build.verbose") ? "-Wall" : "-w"); // show warnings if verbose
	baseCommandCompiler("-ffunction-sections"); // place each function in its own section
	baseCommandCompiler("-fdata-sections");
	baseCommandCompiler("-mmcu=" + boardPreferences.get("build.mcu"));
	baseCommandCompiler("-DF_CPU=" + boardPreferences.get("build.f_cpu"));
	baseCommandCompiler("-DARDUINO=" + Base.REVISION);
	baseCommandCompiler("-DENERGIA=" + Base.EREVISION);
      } else { // default to avr
	baseCommandCompiler(basePath + "avr-gcc");
	baseCommandCompiler("-c"); // compile, don't link
	baseCommandCompiler("-g"); // include debugging info (so errors include line numbers)
	baseCommandCompiler("-Os"); // optimize for size
	baseCommandCompiler(Preferences.getBoolean("build.verbose") ? "-Wall" : "-w"); // show warnings if verbose
	baseCommandCompiler("-ffunction-sections"); // place each function in its own section
	baseCommandCompiler("-fdata-sections");
	baseCommandCompiler("-mmcu=" + boardPreferences.get("build.mcu"));
	baseCommandCompiler("-DF_CPU=" + boardPreferences.get("build.f_cpu"));
	baseCommandCompiler("-MMD"); // output dependancy info
	baseCommandCompiler("-DARDUINO=" + Base.REVISION);
      } else {
	baseCommandCompiler.add(avrBasePath + "gcc");
	baseCommandCompiler.add("-c"); // compile, don't link
      }

    for (int i = 0; i < includePaths.size(); i++) {
      baseCommandCompiler.add("-I" + (String) includePaths.get(i));
    }

    baseCommandCompiler.add(sourceName);
    baseCommandCompiler.add("-o");
    baseCommandCompiler.add(objectName);

    return baseCommandCompiler;
  }
	
	
  static private List getCommandCompilerCPP(String basePath,
    List includePaths, String sourceName, String objectName,
    Map<String, String> boardPreferences) {
    
    String arch = Base.getArch();
    List baseCommandCompilerCPP = new ArrayList();
    if (arch == "msp430") {  
	baseCommandCompiler.add(basePath + "msp430-g++");
	baseCommandCompiler.add("-c"); // compile, don't link
	baseCommandCompiler.add("-g"); // include debugging info (so errors include line numbers)
	baseCommandCompiler.add("-Os"); // optimize for size
	baseCommandCompiler.add(Preferences.getBoolean("build.verbose") ? "-Wall" : "-w"); // show warnings if verbose
	baseCommandCompiler.add("-ffunction-sections"); // place each function in its own section
	baseCommandCompiler.add("-fdata-sections");
	baseCommandCompiler.add("-mmcu=" + boardPreferences.get("build.mcu"));
	baseCommandCompiler.add("-DF_CPU=" + boardPreferences.get("build.f_cpu"));
	baseCommandCompiler.add("-DARDUINO=" + Base.REVISION);
	baseCommandCompiler.add("-DENERGIA=" + Base.EREVISION);
    } else if (arch = "arduino") { // default to avr
	baseCommandCompiler.add(basePath + "avr-g++");
	baseCommandCompiler.add("-c"); // compile, don't link
	baseCommandCompiler.add("-g"); // include debugging info (so errors include line numbers)
	baseCommandCompiler.add("-Os"); // optimize for size
	baseCommandCompiler.add(Preferences.getBoolean("build.verbose") ? "-Wall" : "-w"); // show warnings if verbose
	baseCommandCompiler.add("-fno-exceptions");
	baseCommandCompiler.add("-ffunction-sections"); // place each function in its own section
	baseCommandCompiler.add("-fdata-sections");
	baseCommandCompiler.add("-mmcu=" + boardPreferences.get("build.mcu"));
	baseCommandCompiler.add("-DF_CPU=" + boardPreferences.get("build.f_cpu"));
	baseCommandCompiler.add("-MMD"); // output dependancy info
	baseCommandCompiler.add("-DARDUINO=" + Base.REVISION);
    } else {
      baseCommandCompilerCPP.add(avrBasePath + "g++");
      baseCommandCompilerCPP.add("-c"); // compile, don't link
    }

    for (int i = 0; i < includePaths.size(); i++) {
      baseCommandCompilerCPP.add("-I" + (String) includePaths.get(i));
    }

    baseCommandCompilerCPP.add(sourceName);
    baseCommandCompilerCPP.add("-o");
    baseCommandCompilerCPP.add(objectName);

    return baseCommandCompilerCPP;
  }



  /////////////////////////////////////////////////////////////////////////////

  static private void createFolder(File folder) throws RunnerException {
    if (folder.isDirectory()) return;
    if (!folder.mkdir())
      throw new RunnerException("Couldn't create: " + folder);
  }

  /**
   * Given a folder, return a list of the header files in that folder (but
   * not the header files in its sub-folders, as those should be included from
   * within the header files at the top-level).
   */
  static public String[] headerListFromIncludePath(String path) {
    FilenameFilter onlyHFiles = new FilenameFilter() {
      public boolean accept(File dir, String name) {
        return name.endsWith(".h");
      }
    };
    
    return (new File(path)).list(onlyHFiles);
  }
  
  static public ArrayList<File> findFilesInPath(String path, String extension,
                                                boolean recurse) {
    return findFilesInFolder(new File(path), extension, recurse);
  }
  
  static public ArrayList<File> findFilesInFolder(File folder, String extension,
                                                  boolean recurse) {
    ArrayList<File> files = new ArrayList<File>();
    
    if (folder.listFiles() == null) return files;
    
    for (File file : folder.listFiles()) {
      if (file.getName().startsWith(".")) continue; // skip hidden files
      
      if (file.getName().endsWith("." + extension))
        files.add(file);
        
      if (recurse && file.isDirectory()) {
        files.addAll(findFilesInFolder(file, extension, true));
      }
    }
    
    return files;
  }
}
