# Model persistence uses explicit JSONObject keys, not reflective field names.
# Framework entry points and Media3/Coil/Credentials reflection are covered by
# the manifest/default and library consumer rules. Do not hide missing-class
# warnings globally or disable optimization for the entire application.
-keepattributes Signature,InnerClasses,EnclosingMethod
