	.text
	.globl	main
main:
	la	$2,a
	la	$2,b
	la	$2,end
	la	$2,common
	la	$2,moved
	la	$2,kept
	la	$2,unaligned
	la	$2,half
	la	$2,after
	la	$2,dbl
	li	$2,10
	syscall
	.data
	.byte	1
	.p2align 3,255,2
a:	.8byte	b+4
	.balign	16,,16
b:	.8byte	-1
	.ascii	"\303\251\200\377", "\"\\"
end:
	.word	end-b
	.byte	2
moved:
	.align	2
	.byte	3
kept:
	.p2align 2
	.word	4
	.align	0
	.byte	5
unaligned: .word 6
	.data
	.byte	7
half:	.half	8
after:	.byte	9
dbl:	.double	1.5
	.comm	common,12,8
