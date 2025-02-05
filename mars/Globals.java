package mars;

import mars.mips.instructions.syscalls.*;
import mars.mips.instructions.*;
import mars.mips.hardware.*;
import mars.assembler.*;
import mars.config.ConfigProperties;
import mars.config.SettingsProperties;
import mars.config.SyscallProperties;
import mars.util.*;
import java.util.*;

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining 
a copy of this software and associated documentation files (the 
"Software"), to deal in the Software without restriction, including 
without limitation the rights to use, copy, modify, merge, publish, 
distribute, sublicense, and/or sell copies of the Software, and to 
permit persons to whom the Software is furnished to do so, subject 
to the following conditions:

The above copyright notice and this permission notice shall be 
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF 
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. 
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR 
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF 
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Collection of globally-available data structures.
 * 
 * @author Pete Sanderson
 * @version August 2003
 */
public class Globals {

   /** The set of implemented MIPS instructions. **/
   public static InstructionSet instructionSet;
   /**
    * the program currently being worked with. Used by GUI only, not command line.
    **/
   public static MIPSprogram program;
   /** Symbol table for file currently being assembled. **/
   public static SymbolTable symbolTable;
   /** Simulated MIPS memory component. **/
   public static Memory memory;
   /**
    * Lock variable used at head of synchronized block to guard MIPS memory and
    * registers
    **/
   public static Object memoryAndRegistersLock = new Object();
   /**
    * Flag to determine whether or not to produce internal debugging information.
    **/
   public static boolean debug = false;
   /**
    * Object that contains various settings that can be accessed modified
    * internally.
    **/
   static Settings settings;
   /**
    * String to GUI's RunI/O text area when echoing user input from pop-up dialog.
    */
   public static String userInputAlert = "**** user input : ";
   /** Path to folder that contains images */
   // The leading "/" in filepath prevents package name from being pre-pended.
   public static final String imagesPath = "/images/";
   /** Path to folder that contains help text */
   public static final String helpPath = "/help/";
   /* Flag that indicates whether or not instructionSet has been initialized. */
   private static boolean initialized = false;
   /**
    * The current MARS version number. Can't wait for "initialize()" call to get
    * it.
    */
   public static final String version = "4.5";
   /** List of accepted file extensions for MIPS assembly source files. */
   public static final List<String> fileExtensions = getFileExtensions();
   /** Maximum length of scrolled message window (MARS Messages and Run I/O) */
   public static final int maximumMessageCharacters = getMessageLimit();
   /** Maximum number of assembler errors produced by one assemble operation */
   public static final int maximumErrorMessages = getErrorLimit();
   /** Maximum number of back-step operations to buffer */
   public static final int maximumBacksteps = getBackstepLimit();
   /** MARS copyright years */
   public static final String copyrightYears = getCopyrightYears();
   /** MARS copyright holders */
   public static final String copyrightHolders = getCopyrightHolders();
   /** Placeholder for non-printable ASCII codes */
   public static final String ASCII_NON_PRINT = getAsciiNonPrint();
   /**
    * Array of strings to display for ASCII codes in ASCII display of data segment.
    * ASCII code 0-255 is array index.
    */
   public static final String[] ASCII_TABLE = getAsciiStrings();
   /**
    * MARS exit code -- useful with SYSCALL 17 when running from command line (not
    * GUI)
    */
   public static int exitCode = 0;

   public static boolean runSpeedPanelExists = false;

   private static String getCopyrightYears() {
      return "2003-2014";
   }

   private static String getCopyrightHolders() {
      return "Pete Sanderson and Kenneth Vollmar";
   }

   public static Settings getSettings() {
      return settings;
   }

   private static SettingsProperties settingsProperties = new SettingsProperties();
   private static SyscallProperties syscallProperties = new SyscallProperties();
   private static ConfigProperties configProperties = new ConfigProperties();

   public static void setSettingsProperties(SettingsProperties settingsProperties) {
      Globals.settingsProperties = settingsProperties;
   }

   public static void setSyscallProperties(SyscallProperties syscallProperties) {
      Globals.syscallProperties = syscallProperties;
   }

   public static void setConfigProperties(ConfigProperties configProperties) {
      Globals.configProperties = configProperties;
   }

   public static SettingsProperties getSettingsProperties() {
      return settingsProperties;
   }
    
   public static SyscallProperties getSyscallProperties() {
      return syscallProperties;
   }

   public static ConfigProperties getConfigProperties() {
      return configProperties;
   }

   /**
    * Method called once upon system initialization to create the global data
    * structures.
    **/

   public static void initialize(boolean gui) {
      if (!initialized) {
         memory = Memory.getInstance(); // clients can use Memory.getInstance instead of Globals.memory
         instructionSet = new InstructionSet();
         instructionSet.populate();
         symbolTable = new SymbolTable("global");
         settings = new Settings(gui);
         initialized = true;
         debug = false;
         memory.clear(); // will establish memory configuration from setting
      }
   }

   // Read byte limit of Run I/O or MARS Messages text to buffer.
   private static int getMessageLimit() {
      return Globals.configProperties.getIntegerValue(ConfigProperties.MessageLimit);
   }

   // Read limit on number of error messages produced by one assemble operation.
   private static int getErrorLimit() {
      return Globals.configProperties.getIntegerValue(ConfigProperties.ErrorLimit);
   }

   // Read backstep limit (number of operations to buffer) from properties file.
   private static int getBackstepLimit() {
      return Globals.configProperties.getIntegerValue(ConfigProperties.BackstepLimit);
   }

   // Read ASCII default display character for non-printing characters, from
   // properties file.
   public static String getAsciiNonPrint() {
      String anp =  Globals.configProperties.get(ConfigProperties.AsciiNonPrint);
      return (anp == null) ? "." : ((anp.equals("space")) ? " " : anp);
   }

   // Read ASCII strings for codes 0-255, from properties file. If string
   // value is "null", substitute value of ASCII_NON_PRINT. If string is
   // "space", substitute string containing one space character.
   public static String[] getAsciiStrings() {
      String let = Globals.configProperties.get(ConfigProperties.AsciiTable);
      String placeHolder = getAsciiNonPrint();
      String[] lets = let.split(" +");
      int maxLength = 0;
      for (int i = 0; i < lets.length; i++) {
         if (lets[i].equals("null"))
            lets[i] = placeHolder;
         if (lets[i].equals("space"))
            lets[i] = " ";
         if (lets[i].length() > maxLength)
            maxLength = lets[i].length();
      }
      String padding = "        ";
      maxLength++;
      for (int i = 0; i < lets.length; i++) {
         lets[i] = padding.substring(0, maxLength - lets[i].length()) + lets[i];
      }
      return lets;
   }


   private static List<String> getFileExtensions() {
      List<String> extensionsList = new ArrayList<String>();
      String extensions = Globals.configProperties.get(ConfigProperties.Extensions);
      if (extensions != null) {
         StringTokenizer st = new StringTokenizer(extensions);
         while (st.hasMoreTokens()) {
            extensionsList.add(st.nextToken());
         }
      }
      return extensionsList;
   }



   /**
    * Read any syscall number assignment overrides from config file.
    * 
    * @return ArrayList of SyscallNumberOverride objects
    */
   public List<SyscallNumberOverride> getSyscallOverrides() {
      List<SyscallNumberOverride> overrides = new ArrayList<SyscallNumberOverride>();
      Set<String> properties = Globals.syscallProperties.keySet();
      for(String key : properties) {
         overrides.add(new SyscallNumberOverride(key, Globals.syscallProperties.get(key)));
      }
      return overrides;
   }

}
