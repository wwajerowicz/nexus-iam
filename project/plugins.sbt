resolvers += Resolver.bintrayRepo("bogdanromanx", "maven") // required until sbt-bintray is released
resolvers += Resolver.bintrayRepo("bbp", "nexus-releases")

addSbtPlugin("ch.epfl.bluebrain.nexus" % "sbt-nexus"     % "0.6.1")
addSbtPlugin("com.eed3si9n"            % "sbt-buildinfo" % "0.7.0")
addSbtPlugin("com.lightbend.paradox"   % "sbt-paradox"   % "0.3.0")
