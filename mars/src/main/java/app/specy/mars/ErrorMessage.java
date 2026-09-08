package app.specy.mars;

import app.specy.mars.assembler.SourceLine;
import app.specy.mars.assembler.SourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
/*
Copyright (c) 2003-2012,  Pete Sanderson and Kenneth Vollmar

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
 * Represents occurrance of an error detected during tokenizing, assembly or
 * simulation.
 * 
 * @author Pete Sanderson
 * @version August 2003
 **/

public class ErrorMessage {
   private boolean isWarning; // allow for warnings too (added Nov 2006)
   private String sourcePath;
   private int sourceLine;
   private int sourceColumn;
   private String message;
   private List<SourceLocation> macroExpansionTrace;

   /**
    * Constant to indicate this message is warning not error
    */
   public static final boolean WARNING = true;

   /**
    * Constant to indicate this message is error not warning
    */
   public static final boolean ERROR = false;

   /**
    * Constructor for ErrorMessage.
    * 
    * @param filename String containing name of source file in which this error
    *                 appears.
    * @param line     Line number in source program being processed when error
    *                 occurred.
    * @param position Position within line being processed when error occurred.
    *                 Normally is starting
    *                 position of source token.
    * @param message  String containing appropriate error message.
    * @deprecated Newer constructors replace the String filename parameter with a
    *             MIPSprogram parameter to provide more information.
    **/
   // Added filename October 2006
   @Deprecated
   public ErrorMessage(String filename, int line, int position, String message) {
      this(ERROR, filename, line, position, message, "");
   }

   /**
    * Constructor for ErrorMessage.
    * 
    * @param filename              String containing name of source file in which
    *                              this error appears.
    * @param line                  Line number in source program being processed
    *                              when error occurred.
    * @param position              Position within line being processed when error
    *                              occurred. Normally is starting
    *                              position of source token.
    * @param message               String containing appropriate error message.
    * @param macroExpansionHistory
    * @deprecated Newer constructors replace the String filename parameter with a
    *             MIPSprogram parameter to provide more information.
    **/
   // Added macroExpansionHistory Dec 2012

   @Deprecated
   public ErrorMessage(String filename, int line, int position, String message, String macroExpansionHistory) {
      this(ERROR, filename, line, position, message, macroExpansionHistory);
   }

   /**
    * Constructor for ErrorMessage.
    * 
    * @param isWarning             set to WARNING if message is a warning not
    *                              error, else set to ERROR or omit.
    * @param filename              String containing name of source file in which
    *                              this error appears.
    * @param line                  Line number in source program being processed
    *                              when error occurred.
    * @param position              Position within line being processed when error
    *                              occurred. Normally is starting
    *                              position of source token.
    * @param message               String containing appropriate error message.
    * @param macroExpansionHistory provided so message for macro can include both
    *                              definition and usage line numbers
    * @deprecated Newer constructors replace the String filename parameter with a
    *             MIPSprogram parameter to provide more information.
    **/
   @Deprecated
   public ErrorMessage(boolean isWarning, String filename, int line, int position, String message,
         String macroExpansionHistory) {
      this.isWarning = isWarning;
      this.sourcePath = filename;
      this.sourceLine = line;
      this.sourceColumn = normalizeColumn(line, position);
      this.message = message;
      this.macroExpansionTrace = new ArrayList<>();
   }

   /**
    * Constructor for ErrorMessage. Assumes line number is calculated after any
    * .include files expanded, and
    * if there were, it will adjust filename and line number so message reflects
    * original file and line number.
    * 
    * @param sourceMIPSprogram MIPSprogram object of source file in which this
    *                          error appears.
    * @param line              Line number in source program being processed when
    *                          error occurred.
    * @param position          Position within line being processed when error
    *                          occurred. Normally is starting
    *                          position of source token.
    * @param message           String containing appropriate error message.
    **/

   public ErrorMessage(MIPSprogram sourceMIPSprogram, int line, int position, String message) {
      this(ERROR, sourceMIPSprogram, line, position, message);
   }

   /**
    * Constructor for ErrorMessage. Assumes line number is calculated after any
    * .include files expanded, and
    * if there were, it will adjust filename and line number so message reflects
    * original file and line number.
    * 
    * @param isWarning         set to WARNING if message is a warning not error,
    *                          else set to ERROR or omit.
    * @param sourceMIPSprogram MIPSprogram object of source file in which this
    *                          error appears.
    * @param line              Line number in source program being processed when
    *                          error occurred.
    * @param position          Position within line being processed when error
    *                          occurred. Normally is starting
    *                          position of source token.
    * @param message           String containing appropriate error message.
    **/

   public ErrorMessage(boolean isWarning, MIPSprogram sourceMIPSprogram, int line, int position, String message) {
      this.isWarning = isWarning;
      if (sourceMIPSprogram == null) {
         this.sourcePath = "";
         this.sourceLine = line;
      } else {
         if (sourceMIPSprogram.getSourceLineList() == null || line < 1
               || line > sourceMIPSprogram.getSourceLineList().size()) {
            this.sourcePath = sourceMIPSprogram.getFilename();
            this.sourceLine = line;
         } else {
            SourceLine sourceLine = sourceMIPSprogram.getSourceLineList().get(line - 1);
            this.sourcePath = sourceLine.getSourcePath();
            this.sourceLine = sourceLine.getLineNumber();
         }
      }
      this.sourceColumn = normalizeColumn(this.sourceLine, position);
      this.message = message;
      this.macroExpansionTrace = getExpansionTrace(sourceMIPSprogram);
   }

   /**
    * Constructor for ErrorMessage, to be used for runtime exceptions.
    * 
    * @param statement The ProgramStatement object for the instruction causing the
    *                  runtime error
    * @param message   String containing appropriate error message.
    **/
   // Added January 2013

   public ErrorMessage(ProgramStatement statement, String message) {
      this(statement, 0, message);
   }

   /** Creates a diagnostic at the original source location of a statement. */
   public ErrorMessage(ProgramStatement statement, int position, String message) {
      this.isWarning = ERROR;
      this.sourcePath = statement.getSourcePath();
      this.sourceLine = statement.getSourceLine();
      this.sourceColumn = normalizeColumn(this.sourceLine, position);
      this.message = message;
      this.macroExpansionTrace = statement.getMacroExpansionTrace();
   }

   /**
    * Produce name of file containing error.
    * 
    * @return Returns String containing name of source file containing the error.
    */
   // Added October 2006

   public String getFilename() {
      return sourcePath;
   }

   public String getSourcePath() {
      return sourcePath;
   }

   /**
    * Produce line number of error.
    * 
    * @return Returns line number in source program where error occurred.
    */

   public int getLine() {
      return sourceLine;
   }

   /**
    * Produce position within erroneous line.
    * 
    * @return Returns position within line of source program where error occurred.
    */

   public int getPosition() {
      return sourceColumn;
   }

   /**
    * Produce error message.
    * 
    * @return Returns String containing textual error message.
    */

   public String getMessage() {
      return message;
   }

   /**
    * Determine whether this message represents error or warning.
    * 
    * @return Returns true if this message reflects warning, false if error.
    */
   // Method added 28 Nov 2006
   public boolean isWarning() {
      return this.isWarning;
   }

   /**
    * Returns string describing macro expansion. Empty string if none.
    * 
    * @return string describing macro expansion
    */
   // Method added by Mohammad Sekavat Dec 2012

   public String getMacroExpansionHistory() {
      if (macroExpansionTrace == null || macroExpansionTrace.isEmpty())
         return "";
      return macroExpansionTrace.stream().map(SourceLocation::toString).collect(Collectors.joining(" -> "));
   }

   public List<SourceLocation> getMacroExpansionTrace() {
      return new ArrayList<>(macroExpansionTrace);
   }

   private static List<SourceLocation> getExpansionTrace(MIPSprogram sourceMIPSprogram) {
      if (sourceMIPSprogram == null || sourceMIPSprogram.getLocalMacroPool() == null)
         return new ArrayList<>();
      return sourceMIPSprogram.getLocalMacroPool().getExpansionTrace();
   }

   private static int normalizeColumn(int sourceLine, int sourceColumn) {
      return sourceLine > 0 && sourceColumn < 1 ? 1 : sourceColumn;
   }

   public String generateReport(boolean isWarning){
      ErrorMessage m = this;
      String reportLine = "";
      if ((isWarning && m.isWarning()) || (!isWarning && !m.isWarning())) {
         reportLine = ((isWarning) ? "Warning" : "Error") + " in ";
         if (m.getSourcePath().length() > 0)
            reportLine = reportLine + (m.getSourcePath());
         if (m.getLine() > 0)
            reportLine = reportLine + " line " + m.getLine();
         if (m.getPosition() > 0)
            reportLine = reportLine + " column " + m.getPosition();
         if (!m.getMacroExpansionHistory().isEmpty())
            reportLine = reportLine + " (macro expansion: " + m.getMacroExpansionHistory() + ")";
      }
      return reportLine + ": " + m.getMessage();
   }

   public String toString() {
      return generateReport(false);
   }

}
