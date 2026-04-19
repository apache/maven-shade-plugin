# Serialized Lambda Relocation Test

This integration test verifies that the maven-shade-plugin correctly rewrites
serialized lambda metadata when relocating classes.

## Background

When a lambda expression or method reference uses a `Serializable` functional
interface, the Java compiler generates a `SerializedLambda` object that contains
metadata about the lambda's implementation class. This metadata includes the
class name, which must be rewritten during shading to reflect the relocated
package structure.

## Test Structure

- **MapFunction.java** - A `Serializable` functional interface
- **Main.java** - Uses a method reference (`processor::process`) that creates
  a serialized lambda
- **Processor.java** - Contains the method used as a method reference
- **DataHolder.java** - Simple data class passed through the lambda

## Configuration

The test uses the `<shadeSerializedLambda>true</shadeSerializedLambda>` option:

```xml
<relocation>
    <pattern>org.apache.maven.its.shade.reloc.lambda</pattern>
    <shadedPattern>org.apache.maven.its.shade.reloc.shaded.lambda</shadedPattern>
    <shadeSerializedLambda>true</shadeSerializedLambda>
</relocation>
```

## What the Test Verifies

1. All classes are relocated from `org.apache.maven.its.shade.reloc.lambda` to
   `org.apache.maven.its.shade.reloc.shaded.lambda`
2. The original package paths do NOT exist in the shaded JAR
3. Most importantly: The serialized lambda metadata (in the bytecode's constant
   pool) references the shaded package, not the original

## Running the Test

```bash
cd /path/to/maven-shade-plugin
mvn verify -Prun-its -Dinvoker.test=reloc-serialized-lambda
```

## Expected Behavior

After shading:
- `Main.class` should be at `org/apache/maven/its/shade/reloc/shaded/lambda/Main.class`
- The bytecode should NOT contain references to the original package path
  `org/apache/maven/its/shade/reloc/lambda/Processor`
- All lambda metadata should use the shaded path
  `org/apache/maven/its/shade/reloc/shaded/lambda/Processor`
