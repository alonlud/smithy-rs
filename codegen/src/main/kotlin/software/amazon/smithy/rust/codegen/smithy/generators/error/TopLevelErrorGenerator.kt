/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.smithy.generators.error

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.rustlang.Attribute
import software.amazon.smithy.rust.codegen.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.rustlang.RustMetadata
import software.amazon.smithy.rust.codegen.rustlang.RustModule
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.documentShape
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.smithy.RustCrate

/**
 * Each service defines its own "top-level" error combining all possible errors that a service can emit.
 *
 * Every service error is convertible into this top level error, which enables (if desired) authoring a single error handling
 * path. Eg:
 * ```rust
 * // dynamodb/src/lib.rs
 * enum Error {
 *   ListTablesError(ListTablesError),
 *   ValidationError(ValidationError),
 *   ...,
 *   // It also includes cases from SdkError
 * }
 * ```
 */
class TopLevelErrorGenerator(codegenContext: CodegenContext, private val operations: List<OperationShape>) {
    private val symbolProvider = codegenContext.symbolProvider
    private val model = codegenContext.model

    private val allErrors = operations.flatMap { it.errors }.distinctBy { it.getName(codegenContext.serviceShape) }
        .map { codegenContext.model.expectShape(it, StructureShape::class.java) }
        .sortedBy { it.id.getName(codegenContext.serviceShape) }

    private val sdkError = CargoDependency.SmithyHttp(codegenContext.runtimeConfig).asType().member("result::SdkError")
    fun render(crate: RustCrate) {
        crate.withModule(RustModule.default("error_meta", false)) { writer ->
            writer.renderDefinition()
            writer.renderImplDisplay()
            // Every operation error can be converted into service::Error
            operations.forEach { operationShape ->
                writer.renderImplFrom(operationShape)
            }
            writer.rust("impl #T for Error {}", RuntimeType.StdError)
        }
        crate.lib { it.rust("pub use error_meta::Error;") }
    }

    private fun RustWriter.renderImplDisplay() {
        rustBlock("impl std::fmt::Display for Error") {
            rustBlock("fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result") {
                rustBlock("match self") {
                    allErrors.forEach {
                        rust("Error::${symbolProvider.toSymbol(it).name}(inner) => inner.fmt(f),")
                    }
                    rust("Error::Unhandled(inner) => inner.fmt(f)")
                }
            }
        }
    }

    private fun RustWriter.renderImplFrom(operationShape: OperationShape) {
        val operationError = operationShape.errorSymbol(symbolProvider)
        rustBlock(
            "impl<R> From<#T<#T, R>> for Error where R: Send + Sync + std::fmt::Debug + 'static",
            sdkError,
            operationError
        ) {
            rustBlockTemplate(
                "fn from(err: #{SdkError}<#{OpError}, R>) -> Self",
                "SdkError" to sdkError,
                "OpError" to operationError
            ) {
                rustBlock("match err") {
                    val operationErrors = operationShape.errors.map { model.expectShape(it) }
                    rustBlock("#T::ServiceError { err, ..} => match err.kind", sdkError) {
                        operationErrors.forEach { errorShape ->
                            val errSymbol = symbolProvider.toSymbol(errorShape)
                            rust("#TKind::${errSymbol.name}(inner) => Error::${errSymbol.name}(inner),", operationError)
                        }
                        rust("#TKind::Unhandled(inner) => Error::Unhandled(inner),", operationError)
                    }
                    rust("_ => Error::Unhandled(err.into()),")
                }
            }
        }
    }

    private fun RustWriter.renderDefinition() {
        rust("/// All possible error types for this service.")
        RustMetadata(
            additionalAttributes = listOf(Attribute.NonExhaustive),
            public = true
        ).withDerives(RuntimeType.Debug).render(this)
        rustBlock("enum Error") {
            allErrors.forEach { error ->
                documentShape(error, model)
                val sym = symbolProvider.toSymbol(error)
                rust("${sym.name}(#T),", sym)
            }
            rust("/// An unhandled error occurred.")
            rust("Unhandled(Box<dyn #T + Send + Sync + 'static>)", RuntimeType.StdError)
        }
    }
}
