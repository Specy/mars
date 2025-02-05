package app.specy.mars.mips.instructions.syscalls;

import app.specy.mars.*;

/**
 * Abstract class that a MIPS syscall system service may extend. A qualifying
 * service
 * must be a class in the mars.mips.instructions.syscalls package that
 * implements the Syscall interface, must be compiled into a .class file,
 * and its .class file must be in the same folder as Syscall.class.
 * Mars will detect a qualifying syscall upon startup, create an instance
 * using its no-argument constructor and add it to its syscall list.
 * When its service is invoked at runtime ("syscall" instruction
 * with its service number stored in register $v0), its simulate()
 * method will be invoked.
 *
 */

public abstract class AbstractSyscall implements Syscall {
   private int serviceNumber;
   private String serviceName;

   /**
    * Constructor is provided so subclass may initialize instance variables.
    * 
    * @param number default assigned service number
    * @param name   service name which may be used for reference independent of
    *               number
    */
   public AbstractSyscall(int number, String name) {
      serviceNumber = number;
      serviceName = name;
   }

   /**
    * Return the name you have chosen for this syscall. This can be used by a MARS
    * user to refer to the service when choosing to override its default service
    * number in the configuration file.
    * 
    * @return service name as a string
    */
   public String getName() {
      return serviceName;
   }

   /**
    * Set the service number. This is provided to allow MARS implementer or user
    * to override the default service number.
    * 
    * @param num specified service number to override the default.
    */
   public void setNumber(int num) {
      serviceNumber = num;
   }

   /**
    * Return the assigned service number. This is the number the MIPS programmer
    * must store into $v0 before issuing the SYSCALL instruction.
    * 
    * @return assigned service number
    */
   public int getNumber() {
      return serviceNumber;
   }

   /**
    * Performs syscall function. It will be invoked when the service is invoked
    * at simulation time. Service is identified by value stored in $v0.
    * 
    * @param statement ProgramStatement object for this syscall instruction.
    */
   public abstract void simulate(ProgramStatement statement)
         throws ProcessingException;
}