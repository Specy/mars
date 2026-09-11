package app.specy.mars.assembler;

import java.util.ArrayList;

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
 * Class representing MIPS assembler directives. If Java had enumerated types,
 * these
 * would probably be implemented that way. Each directive is represented by a
 * unique object.
 * The directive name is indicative of the directive it represents. For example,
 * DATA
 * represents the MIPS .data directive.
 * 
 * @author Pete Sanderson
 * @version August 2003
 **/

public final class Directives {

   private static ArrayList directiveList = new ArrayList();
   public static final Directives DATA = new Directives(".data",
         "Subsequent items stored in Data segment at next available address");
   public static final Directives TEXT = new Directives(".text",
         "Subsequent items (instructions) stored in Text segment at next available address");
   public static final Directives WORD = new Directives(".word",
         "Store the listed value(s) as 32 bit words on word boundary");
   public static final Directives ASCII = new Directives(".ascii",
         "Store the string in the Data segment but do not add null terminator");
   public static final Directives ASCIIZ = new Directives(".asciiz",
         "Store the string in the Data segment and add null terminator");
   public static final Directives BYTE = new Directives(".byte", "Store the listed value(s) as 8 bit bytes");
   public static final Directives ALIGN = new Directives(".align",
         "Align next data item on specified byte boundary (0=byte, 1=half, 2=word, 3=double)");
   public static final Directives HALF = new Directives(".half",
         "Store the listed value(s) as 16 bit halfwords on halfword boundary");
   public static final Directives SPACE = new Directives(".space",
         "Reserve the next specified number of bytes in Data segment");
   public static final Directives DOUBLE = new Directives(".double",
         "Store the listed value(s) as double precision floating point");
   public static final Directives FLOAT = new Directives(".float",
         "Store the listed value(s) as single precision floating point");
   public static final Directives EXTERN = new Directives(".extern",
         "Declare the listed label and byte length to be a global data field");
   public static final Directives KDATA = new Directives(".kdata",
         "Subsequent items stored in Kernel Data segment at next available address");
   public static final Directives KTEXT = new Directives(".ktext",
         "Subsequent items (instructions) stored in Kernel Text segment at next available address");
   public static final Directives GLOBL = new Directives(".globl",
         "Declare the listed label(s) as global to enable referencing from other files");
   public static final Directives SET = new Directives(".set",
         "Set assembler variables.  Currently ignored but included for SPIM compatability");
   /* EQV added by DPS 11 July 2012 */
   public static final Directives EQV = new Directives(".eqv",
         "Substitute second operand for first. First operand is symbol, second operand is expression (like #define)");
   /* MACRO and END_MACRO added by Mohammad Sekhavat Oct 2012 */
   public static final Directives MACRO = new Directives(".macro", "Begin macro definition.  See .end_macro");
   public static final Directives END_MACRO = new Directives(".end_macro", "End macro definition.  See .macro");
   /* INCLUDE added by DPS 11 Jan 2013 */
   public static final Directives INCLUDE = new Directives(".include",
         "Insert the contents of the specified file.  Put filename in quotes.");

   /* Directives below this point exist so that the output of a C compiler assembles. */
   public static final Directives SECTION = new Directives(".section",
         "Switch to the named section. A read-only or zeroed section becomes part of the data segment here");
   public static final Directives RDATA = new Directives(".rdata",
         "Subsequent items stored in the Data segment. Read-only data is not separated out in this simulator");
   public static final Directives SDATA = new Directives(".sdata", "Alias for .rdata");
   public static final Directives BSS = new Directives(".bss",
         "Subsequent items stored in the Data segment, which starts out zeroed");
   public static final Directives SBSS = new Directives(".sbss", "Alias for .bss");
   public static final Directives ZERO = new Directives(".zero",
         "Reserve the next specified number of bytes, which read as zero. Alias for .space");
   public static final Directives COMM = new Directives(".comm",
         "Reserve the given number of bytes for a global symbol, the way a C compiler declares an uninitialized global variable. Takes a symbol, a size in bytes and an optional alignment");
   public static final Directives LCOMM = new Directives(".lcomm",
         "Reserve the given number of bytes for a symbol local to this file, the way a C compiler declares an uninitialized static variable");
   public static final Directives P2ALIGN = new Directives(".p2align", "Alias for .align");
   public static final Directives BALIGN = new Directives(".balign",
         "Align next data item on the given byte boundary, written directly rather than as a power of two");
   public static final Directives TWO_BYTE = new Directives(".2byte", "Alias for .half");
   public static final Directives FOUR_BYTE = new Directives(".4byte", "Alias for .word");
   public static final Directives ASCIZ = new Directives(".asciz", "Alias for .asciiz");
   public static final Directives STRING = new Directives(".string", "Alias for .asciiz");
   public static final Directives GLOBAL = new Directives(".global", "Alias for .globl");

   private String descriptor;
   private String description; // help text

   private Directives() {
      // private ctor assures no objects can be created other than those above.
      this.descriptor = "generic";
      this.description = "";
      directiveList.add(this);
   }

   private Directives(String name, String description) {
      this.descriptor = name;
      this.description = description;
      directiveList.add(this);
   }

   /**
    * Find Directive object, if any, which matches the given String.
    * 
    * @param str A String containing candidate directive name (e.g. ".ascii")
    * @return If match is found, returns matching Directives object, else returns
    *         <tt>null</tt>.
    **/

   /**
    * Reduce an alias to the directive it is spelled differently from, so that the
    * rest of the assembler only ever sees the canonical one. The alias keeps its own
    * entry in the directive list, so it is still offered by autocomplete and carries
    * its own description.
    *
    * @param direct a directive, possibly an alias
    * @return the directive that does the work, or the argument unchanged
    **/
   public static Directives canonical(Directives direct) {
      if (direct == TWO_BYTE)
         return HALF;
      if (direct == FOUR_BYTE)
         return WORD;
      if (direct == P2ALIGN)
         return ALIGN;
      if (direct == ZERO)
         return SPACE;
      if (direct == ASCIZ || direct == STRING)
         return ASCIIZ;
      if (direct == GLOBAL)
         return GLOBL;
      if (direct == SDATA)
         return RDATA;
      if (direct == SBSS)
         return BSS;
      return direct;
   }

   public static Directives matchDirective(String str) {
      Directives match;
      for (int i = 0; i < directiveList.size(); i++) {
         match = (Directives) directiveList.get(i);
         if (str.equalsIgnoreCase(match.descriptor)) {
            return match;
         }
      }
      return null;
   }

   /**
    * Find Directive object, if any, which contains the given string as a prefix.
    * For example,
    * ".a" will match ".ascii", ".asciiz" and ".align"
    * 
    * @param str A String
    * @return If match is found, returns ArrayList of matching Directives objects,
    *         else returns <tt>null</tt>.
    **/

   public static ArrayList prefixMatchDirectives(String str) {
      ArrayList matches = null;
      for (int i = 0; i < directiveList.size(); i++) {
         if (((Directives) directiveList.get(i)).descriptor.toLowerCase().startsWith(str.toLowerCase())) {
            if (matches == null) {
               matches = new ArrayList();
            }
            matches.add(directiveList.get(i));
         }
      }
      return matches;
   }

   /**
    * Produces String-ified version of Directive object
    * 
    * @return String representing Directive: its MIPS name
    **/

   public String toString() {
      return this.descriptor;
   }

   /**
    * Get name of this Directives object
    * 
    * @return name of this MIPS directive as a String
    **/

   public String getName() {
      return this.descriptor;
   }

   /**
    * Get description of this Directives object
    * 
    * @return description of this MIPS directive (for help purposes)
    **/

   public String getDescription() {
      return this.description;
   }

   /**
    * Produces List of Directive objects
    * 
    * @return MIPS Directive
    **/
   public static ArrayList getDirectiveList() {
      return directiveList;
   }

   /**
    * Lets you know whether given directive is for integer (WORD,HALF,BYTE).
    * 
    * @param direct a MIPS directive
    * @return true if given directive is FLOAT or DOUBLE, false otherwise
    **/
   public static boolean isIntegerDirective(Directives direct) {
      return direct == Directives.WORD || direct == Directives.HALF || direct == Directives.BYTE;
   }

   /**
    * Lets you know whether given directive is for floating number (FLOAT,DOUBLE).
    *
    * @param direct a MIPS directive
    * @return true if given directive is FLOAT or DOUBLE, false otherwise.
    **/
   public static boolean isFloatingDirective(Directives direct) {
      return direct == Directives.FLOAT || direct == Directives.DOUBLE;
   }

}