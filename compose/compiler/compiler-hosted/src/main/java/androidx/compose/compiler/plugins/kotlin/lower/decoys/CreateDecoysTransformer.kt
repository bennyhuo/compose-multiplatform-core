/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.compiler.plugins.kotlin.lower.decoys

import androidx.compose.compiler.plugins.kotlin.ModuleMetrics
import androidx.compose.compiler.plugins.kotlin.analysis.StabilityInferencer
import androidx.compose.compiler.plugins.kotlin.lower.ModuleLoweringPass
import androidx.compose.compiler.plugins.kotlin.lower.function
import androidx.compose.compiler.plugins.kotlin.lower.isSyntheticComposableFunction
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureSerializer
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.IrFunctionBuilder
import org.jetbrains.kotlin.ir.builders.declarations.buildConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeArgument
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * Copies each IR declaration that won't match descriptors after Compose transforms (see [shouldBeRemapped]).
 * Original function are kept to match descriptors with a stubbed body, all other transforms are
 * applied to the copied version only.
 *
 * Example:
 * ```
 * @Composable
 * fun A(x: Any) {}
 * ```
 *
 * is transformed into:
 *
 * ```
 * @Decoy(targetName="A$composable")
 * fun A(x: Any) {
 *  illegalDecoyCallException("A")
 * }
 *
 * @Composable
 * @DecoyImplementation("A$composable")
 * fun A$composable(x: Any) {}
 * ```
 */
class CreateDecoysTransformer(
    pluginContext: IrPluginContext,
    symbolRemapper: DeepCopySymbolRemapper,
    signatureBuilder: IdSignatureSerializer,
    stabilityInferencer: StabilityInferencer,
    metrics: ModuleMetrics,
) : AbstractDecoysLowering(
    pluginContext = pluginContext,
    symbolRemapper = symbolRemapper,
    metrics = metrics,
    stabilityInferencer = stabilityInferencer,
    signatureBuilder = signatureBuilder
), ModuleLoweringPass {

    private val originalFunctions: MutableMap<IrFunction, IrDeclarationParent> = mutableMapOf()

    private val decoyAnnotation by lazy {
        getTopLevelClass(DecoyClassIds.Decoy).owner
    }

    private val decoyImplementationAnnotation by lazy {
        getTopLevelClass(DecoyClassIds.DecoyImplementation).owner
    }

    private val decoyImplementationDefaultsBitmaskAnnotation =
        getTopLevelClass(DecoyClassIds.DecoyImplementationDefaultsBitMask).owner

    private val decoyStub by lazy {
        getTopLevelFunction(DecoyCallableIds.illegalDecoyCallException).owner
    }

    override fun lower(module: IrModuleFragment) {
        module.transformChildrenVoid()
        updateParents()
        module.patchDeclarationParents()
    }

    fun updateParents() {
        originalFunctions.forEach { (f, parent) ->
            (parent as? IrDeclarationContainer)?.addChild(f)
        }
        originalFunctions.clear()
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        if (!declaration.shouldBeRemapped()) {
            return super.visitSimpleFunction(declaration)
        }

        val newName = declaration.decoyImplementationName()
        val copied = declaration.copyWithName(newName) as IrSimpleFunction
        copied.parent = declaration.parent
        originalFunctions += copied to declaration.parent

        // "copied" has new symbols (due to deepCopyWithSymbols).
        // Therefore, we need to recurse into the copied version.
        // Otherwise, inner `copied` functions can be added to a parent
        // that is not in the IR tree anymore (due to a body removal from decoy - see `stubBody`).
        // The use cases:
        // 1) A @Composable function declaring an anonymous object implementing
        // an interface with @Composable function.
        // 2) A @Composable function declaring a local class with a @Composable function.
        super.visitSimpleFunction(copied) as IrSimpleFunction

        return declaration.apply {
            setDecoyAnnotation(newName.asString())

            valueParameters.forEach { it.defaultValue = null }
            if (body != null) {
                stubBody()
            }
        }
    }

    override fun visitConstructor(declaration: IrConstructor): IrStatement {
        if (!declaration.shouldBeRemapped()) {
            return super.visitConstructor(declaration)
        }

        val newName = declaration.decoyImplementationName()
        val copied = declaration.copyWithName(
            newName, context.irFactory::buildConstructor
        ) as IrConstructor
        copied.parent = declaration.parent
        originalFunctions += copied to declaration.parent

        // "copied" has new symbols (due to deepCopyWithSymbols).
        // Therefore, we need to recurse into the copied version.
        // See the comment in visitSimpleFunction for an explanation.
        super.visitConstructor(copied) as IrConstructor

        return declaration.apply {
            // keep the original delegating constructor call to keep the IR valid (according to kotlin backend expectations)
            val delegatingConstructorCall = this.body?.statements?.firstOrNull {
                it is IrDelegatingConstructorCall
            }
            setDecoyAnnotation(newName.asString())
            stubBody(delegatingConstructorCall)
        }
    }

    private fun IrFunction.decoyImplementationName(): Name {
        return dexSafeName(
            Name.identifier(name.asString() + IMPLEMENTATION_FUNCTION_SUFFIX)
        )
    }

    private fun IrFunction.copyWithName(
        newName: Name,
        factory: (IrFunctionBuilder.() -> Unit) -> IrFunction = context.irFactory::buildFun
    ): IrFunction {
        val original = this
        val newFunction = factory {
            updateFrom(original)
            name = newName
            returnType = original.returnType
            isPrimary = (original as? IrConstructor)?.isPrimary ?: false
            isOperator = false
        }
        newFunction.annotations = original.annotations
        newFunction.metadata = original.metadata

        if (newFunction is IrSimpleFunction) {
            newFunction.overriddenSymbols = (original as IrSimpleFunction).overriddenSymbols
            newFunction.correspondingPropertySymbol = null
        }
        newFunction.origin = original.origin

        // here generic value parameters will be applied
        newFunction.copyTypeParametersFrom(original)

        // ..but we need to remap the return type as well
        newFunction.returnType = newFunction.returnType.remapTypeParameters(
            source = original,
            target = newFunction
        )
        newFunction.valueParameters = original.valueParameters.map {
            val name = dexSafeName(it.name).asString()
            it.copyTo(
                newFunction,
                // remove leading $ in params to avoid confusing other transforms
                name = Name.identifier(name.dropWhile { it == '$' }),
                type = it.type.remapTypeParameters(original, newFunction),
                // remapping the type parameters explicitly
                defaultValue = it.defaultValue?.copyWithNewTypeParams(original, newFunction)
            )
        }
        newFunction.dispatchReceiverParameter =
            original.dispatchReceiverParameter?.copyTo(newFunction)
        newFunction.extensionReceiverParameter =
            original.extensionReceiverParameter?.copyWithNewTypeParams(original, newFunction)

        newFunction.body = original.moveBodyTo(newFunction)
            ?.copyWithNewTypeParams(original, newFunction)

        val oldBody = original.body
        // we need to clean the original body before types remapping (to not remap body, it's moved to a new function).
        // also see fun IrFunction.stubBody
        original.body = null

        // we have to remap original types (in parameters) to get rid of ComposableFunctionX references.
        // this way the `original` will produce a correct signature stored in DecoyImplementation annotation
        original.remapComposableFunctionReferences()

        newFunction.addDecoyImplementationAnnotation(newName.asString(), original.getSignatureId())

        newFunction.valueParameters.forEach {
            it.defaultValue?.transformDefaultValue(
                originalFunction = original,
                newFunction = newFunction
            )
        }

        // restore the old body to make `stubBody` work correctly (only abstract functions can have empty body)
        original.body = oldBody

        return newFunction
    }

    private fun IrFunction.remapComposableFunctionReferences() {
        this.remapTypes(object : TypeRemapper {
            override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {}
            override fun leaveScope() {}

            private fun remapTypeArgument(typeArgument: IrTypeArgument): IrTypeArgument =
                if (typeArgument is IrTypeProjection)
                    makeTypeProjection(this.remapType(typeArgument.type), typeArgument.variance)
                else
                    typeArgument

            override fun remapType(type: IrType): IrType {
                if (type !is IrSimpleType) return type
                if (type.isSyntheticComposableFunction()) {
                    val oldIrArguments = type.arguments
                    val functionCls = context.function(oldIrArguments.size - 1)
                    return IrSimpleTypeImpl(
                        null,
                        functionCls,
                        type.nullability,
                        oldIrArguments.map { remapTypeArgument(it) },
                        type.annotations,
                        null
                    )
                }
                return type
            }
        })
    }

    /**
     *  Expressions for default values can use other parameters.
     *  In such cases we need to ensure that default values expressions use parameters of the new
     *  function (new/copied value parameters).
     *
     *  Example:
     *  fun Foo(a: String, b: String = a) {...}
     */
    private fun IrExpressionBody.transformDefaultValue(
        originalFunction: IrFunction,
        newFunction: IrFunction
    ) {
        transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val original = super.visitGetValue(expression)
                val valueParameter =
                    (expression.symbol.owner as? IrValueParameter) ?: return original

                val parameterIndex = valueParameter.index
                if (parameterIndex < 0 || valueParameter.parent != originalFunction) {
                    return super.visitGetValue(expression)
                }
                return irGet(newFunction.valueParameters[parameterIndex])
            }
        })
    }

    private fun IrFunction.stubBody(vararg statements: IrStatement?) {
        body = DeclarationIrBuilder(context, symbol).irBlockBody {
            statements.filterNotNull().forEach { + it }
            + irReturn(
                irCall(decoyStub).also { call ->
                    call.putValueArgument(0, irConst(name.asString()))
                }
            )
        }
    }

    private fun IrFunction.setDecoyAnnotation(implementationName: String) {
        annotations = listOf(
            IrConstructorCallImpl.fromSymbolOwner(
                type = decoyAnnotation.defaultType,
                constructorSymbol = decoyAnnotation.constructors.first().symbol
            ).also {
                it.putValueArgument(0, irConst(implementationName))
                it.putValueArgument(1, irVarargString(emptyList()))
            }
        )
    }

    private fun IrFunction.addDecoyImplementationAnnotation(name: String, signatureId: Long) {
        annotations = annotations +
            IrConstructorCallImpl.fromSymbolOwner(
                type = decoyImplementationAnnotation.defaultType,
                constructorSymbol = decoyImplementationAnnotation.constructors.first().symbol
            ).also {
                it.putValueArgument(0, irConst(name))
                it.putValueArgument(1, irConst(signatureId))
            }

        annotations = annotations +
            IrConstructorCallImpl.fromSymbolOwner(
                type = decoyImplementationDefaultsBitmaskAnnotation.defaultType,
                constructorSymbol =
                    decoyImplementationDefaultsBitmaskAnnotation.constructors.first().symbol
            ).also {
                val paramsWithDefaultsBitMask = bitMask(
                    *valueParameters.map { it.hasDefaultValue() }.toBooleanArray()
                )
                it.putValueArgument(0, irConst(paramsWithDefaultsBitMask))
            }
    }

    companion object {
        private const val IMPLEMENTATION_FUNCTION_SUFFIX = "\$composable"
    }
}
