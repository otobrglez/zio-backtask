with (import <nixpkgs> {});

mkShell {
  buildInputs = [
    jdk17_headless
    sbt
    redis
 ];
  shellHook = ''
    export BACKTASK_HOME=`pwd`
  '';
}
