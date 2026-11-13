// main.c — process entry point for the SSTVAF desktop CLI.
//
// Deliberately trivial: all logic lives in sstvaf_cli_run() so it can be unit
// tested (test_sstvaf_cli.c) without spawning a process.

#include "sstvaf_cli.h"

int main(int argc, char** argv)
{
    return sstvaf_cli_run(argc, argv);
}
