((identifier) @variable
 (#match? @variable "^[a-z_]")
 [
   ("static_cast")
   ("reinterpret_cast")
 ] @keyword)
