use proc_macro::TokenStream;
use quote::quote;
use syn::meta::ParseNestedMeta;
use syn::parse::Parser;
use syn::{parse_macro_input, FnArg, ItemFn, Pat, ReturnType, Signature, Type};

fn validate_name(name: &str) -> Result<(), String> {
    cherry_test_core_naming::parse_test_name(name)
        .map(|_| ())
        .map_err(|e| e.to_string())
}

mod cherry_test_core_naming {
    pub fn parse_test_name(full_name: &str) -> Result<(), String> {
        const PROJECT: &str = "_project_";
        const SUITE: &str = "_suite_";
        const TEST: &str = "_test";

        if !full_name.ends_with(TEST) {
            return Err(format!(
                "cherry-test name `{full_name}` must end with `_test`; expected \
                 {{project}}_project_{{suite}}_suite_{{test}}_test"
            ));
        }
        let stem = full_name.strip_suffix(TEST).unwrap();
        let suite_idx = stem.rfind(SUITE).ok_or_else(|| naming_error(full_name))?;
        let (before_suite, test) = stem.split_at(suite_idx);
        let test = test
            .strip_prefix(SUITE)
            .ok_or_else(|| naming_error(full_name))?;
        let project_idx = before_suite
            .find(PROJECT)
            .ok_or_else(|| naming_error(full_name))?;
        let (project, suite_part) = before_suite.split_at(project_idx);
        let suite = suite_part
            .strip_prefix(PROJECT)
            .ok_or_else(|| naming_error(full_name))?;
        if project.is_empty() || suite.is_empty() || test.is_empty() {
            return Err(naming_error(full_name));
        }
        Ok(())
    }

    fn naming_error(full_name: &str) -> String {
        format!(
            "cherry-test name `{full_name}` must match \
             {{project}}_project_{{suite}}_suite_{{test}}_test"
        )
    }
}

fn parse_attrs_tokens(
    attr2: proc_macro2::TokenStream,
) -> Result<(Option<String>, Option<String>), String> {
    if attr2.is_empty() {
        return Ok((None, None));
    }
    let mut scope = None;
    let mut auth = None;
    let parser = |meta: ParseNestedMeta| -> syn::Result<()> {
        if meta.path.is_ident("scope") {
            let value: syn::LitStr = meta.value()?.parse()?;
            scope = Some(value.value());
        } else if meta.path.is_ident("auth") {
            let value: syn::LitStr = meta.value()?.parse()?;
            auth = Some(value.value());
        } else {
            return Err(meta.error(format!(
                "unknown cherry_test attribute `{}`",
                meta.path
                    .get_ident()
                    .map(|i| i.to_string())
                    .unwrap_or_else(|| "???".into())
            )));
        }
        Ok(())
    };
    syn::meta::parser(parser)
        .parse2(attr2)
        .map_err(|e| e.to_string())?;
    Ok((scope, auth))
}

fn parse_attrs(attr: TokenStream) -> Result<(Option<String>, Option<String>), String> {
    let attr2: proc_macro2::TokenStream = attr.into();
    parse_attrs_tokens(attr2)
}

fn extract_env_arg(sig: &Signature) -> bool {
    sig.inputs.len() == 1
        && matches!(
            sig.inputs.first(),
            Some(FnArg::Typed(pat_type)) if matches!(
                &*pat_type.pat,
                Pat::Ident(ident) if ident.ident == "env"
            ) && matches!(&*pat_type.ty, Type::Path(type_path) if type_path.path.is_ident("TestEnv"))
        )
}

#[proc_macro_attribute]
pub fn cherry_test(attr: TokenStream, item: TokenStream) -> TokenStream {
    let input = parse_macro_input!(item as ItemFn);
    let fn_name = input.sig.ident.to_string();

    let (scope, auth) = match parse_attrs(attr) {
        Ok(values) => values,
        Err(message) => {
            return syn::Error::new_spanned(&input.sig.ident, message)
                .to_compile_error()
                .into();
        }
    };

    if let Err(message) = validate_name(&fn_name) {
        return syn::Error::new_spanned(&input.sig.ident, message)
            .to_compile_error()
            .into();
    }

    if !input.sig.asyncness.is_some() {
        return syn::Error::new_spanned(
            &input.sig,
            "cherry-test integration tests must be async fn",
        )
        .to_compile_error()
        .into();
    }

    if !extract_env_arg(&input.sig) {
        return syn::Error::new_spanned(
            &input.sig,
            "cherry-test integration tests must take a single `env: TestEnv` parameter",
        )
        .to_compile_error()
        .into();
    }

    if !matches!(input.sig.output, ReturnType::Default) {
        return syn::Error::new_spanned(
            &input.sig,
            "cherry-test integration tests must not declare a return type",
        )
        .to_compile_error()
        .into();
    }

    let fn_ident = &input.sig.ident;
    let fn_name_lit = fn_name.as_str();
    let body = &input.block;

    let ignore_label = match (&scope, &auth) {
        (Some(s), Some(a)) => format!("cherry-test integration scope {s} auth {a}"),
        (Some(s), None) => format!("cherry-test integration scope {s}"),
        (None, Some(a)) => format!("cherry-test integration auth {a}"),
        (None, None) => "cherry-test integration scope default".to_string(),
    };
    let ignore_lit = syn::LitStr::new(&ignore_label, proc_macro2::Span::call_site());

    let scope_arg = match &scope {
        Some(s) => quote! { Some(#s) },
        None => quote! { None },
    };
    let auth_arg = match &auth {
        Some(a) => quote! { Some(#a) },
        None => quote! { None },
    };

    let expanded = quote! {
        #[::tokio::test]
        #[ignore = #ignore_lit]
        async fn #fn_ident() {
            ::cherry_test_core::integration::run(
                #fn_name_lit,
                env!("CARGO_PKG_NAME"),
                #scope_arg,
                #auth_arg,
                async |env: ::cherry_test_core::TestEnv| -> ::anyhow::Result<()> {
                    #body
                    Ok(())
                },
            )
            .await
            .expect("cherry-test integration test failed");
        }
    };

    expanded.into()
}

#[cfg(test)]
mod tests {
    use super::parse_attrs_tokens;

    fn parse_attr_str(input: &str) -> Result<(Option<String>, Option<String>), String> {
        let tokens: proc_macro2::TokenStream = input.parse().expect("tokenize");
        parse_attrs_tokens(tokens)
    }

    #[test]
    fn parses_scope_only_attr() {
        let (scope, auth) = parse_attr_str("scope = \"mini_wallet\"").unwrap();
        assert_eq!(scope.as_deref(), Some("mini_wallet"));
        assert_eq!(auth, None);
    }

    #[test]
    fn parses_auth_only_attr() {
        let (scope, auth) = parse_attr_str("auth = \"okta\"").unwrap();
        assert_eq!(scope, None);
        assert_eq!(auth.as_deref(), Some("okta"));
    }

    #[test]
    fn parses_combined_scope_and_auth_attr() {
        let (scope, auth) = parse_attr_str("scope = \"mini_auth\", auth = \"okta\"").unwrap();
        assert_eq!(scope.as_deref(), Some("mini_auth"));
        assert_eq!(auth.as_deref(), Some("okta"));
    }
}
