class Outer(val name : String):
  class Inner:
    def thump = s"${name}'s heartbeat"
    val o = Outer.this

def stethoscope( inner : Outer#Inner ) = inner.thump

// def outerName( inner : Outer#Inner ) : String = ???

val joe = new Outer("joe")
val inner = new joe.Inner

@main
def main =
  println( stethoscope(inner) )
