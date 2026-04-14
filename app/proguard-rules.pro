# Keep JNI callback interface so native code can call back into Java
-keep interface com.example.qwenchat.LlamaEngine$TokenCallback { *; }
-keep class com.example.qwenchat.LlamaEngine { *; }
