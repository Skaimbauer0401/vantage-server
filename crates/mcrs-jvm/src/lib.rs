//! Embeds a HotSpot JVM inside the mcrs process.
//!
//! `jvm.dll` / `libjvm.so` is loaded at runtime (no build-time linking), so any
//! JDK can be used without rebuilding.

pub mod bridge;
mod chunk_io;

use std::ffi::{c_void, CString};
use std::path::{Path, PathBuf};
use std::ptr;

use anyhow::{bail, Context, Result};
use jni::objects::{JObject, JValue};
use jni::sys::{jint, JavaVMInitArgs, JavaVMOption, JNI_OK, JNI_TRUE, JNI_VERSION_10};
use jni::JavaVM;

type CreateJavaVm =
    unsafe extern "system" fn(*mut *mut jni::sys::JavaVM, *mut *mut c_void, *mut c_void) -> jint;

/// A running JVM. The shared library stays loaded for the lifetime of this value.
pub struct Jvm {
    vm: JavaVM,
    _lib: libloading::Library,
}

/// Returns the major version from a JDK's `release` file, e.g. 25.
pub fn java_major_version(java_home: &Path) -> Result<u32> {
    let release = std::fs::read_to_string(java_home.join("release"))
        .with_context(|| format!("{} is not a JDK (no release file)", java_home.display()))?;
    let line = release
        .lines()
        .find_map(|l| l.strip_prefix("JAVA_VERSION="))
        .context("release file has no JAVA_VERSION")?;
    let version = line.trim_matches('"');
    let major = version.split(['.', '+', '-']).next().unwrap_or(version);
    major.parse().with_context(|| format!("bad JAVA_VERSION {version}"))
}

fn jvm_library_path(java_home: &Path) -> PathBuf {
    if cfg!(windows) {
        java_home.join("bin/server/jvm.dll")
    } else if cfg!(target_os = "macos") {
        java_home.join("lib/server/libjvm.dylib")
    } else {
        java_home.join("lib/server/libjvm.so")
    }
}

#[cfg(windows)]
unsafe fn load_jvm_library(java_home: &Path) -> Result<libloading::Library> {
    use libloading::os::windows::{
        Library, LOAD_LIBRARY_SEARCH_DEFAULT_DIRS, LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR,
    };
    // jvm.dll depends on DLLs in <java_home>/bin (vcruntime, etc.).
    let bin = java_home.join("bin");
    let path = std::env::var_os("PATH").unwrap_or_default();
    let mut paths = vec![bin];
    paths.extend(std::env::split_paths(&path));
    std::env::set_var("PATH", std::env::join_paths(paths)?);

    let lib = Library::load_with_flags(
        jvm_library_path(java_home),
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS,
    )?;
    Ok(lib.into())
}

#[cfg(not(windows))]
unsafe fn load_jvm_library(java_home: &Path) -> Result<libloading::Library> {
    Ok(libloading::Library::new(jvm_library_path(java_home))?)
}

impl Jvm {
    /// Loads the JVM from `java_home` and starts it with the given `-X`/`-D`/`--` options.
    pub fn create(java_home: &Path, options: &[String]) -> Result<Jvm> {
        let lib = unsafe { load_jvm_library(java_home) }
            .with_context(|| format!("loading {}", jvm_library_path(java_home).display()))?;
        let create: libloading::Symbol<CreateJavaVm> = unsafe { lib.get(b"JNI_CreateJavaVM\0")? };

        let c_options = options
            .iter()
            .map(|o| CString::new(o.as_str()))
            .collect::<Result<Vec<_>, _>>()?;
        let mut raw_options: Vec<JavaVMOption> = c_options
            .iter()
            .map(|o| JavaVMOption { optionString: o.as_ptr() as *mut _, extraInfo: ptr::null_mut() })
            .collect();
        let mut args = JavaVMInitArgs {
            version: JNI_VERSION_10,
            nOptions: raw_options.len() as jint,
            options: raw_options.as_mut_ptr(),
            ignoreUnrecognized: JNI_TRUE,
        };

        let mut vm_ptr: *mut jni::sys::JavaVM = ptr::null_mut();
        let mut env_ptr: *mut c_void = ptr::null_mut();
        let rc = unsafe { create(&mut vm_ptr, &mut env_ptr, &mut args as *mut _ as *mut c_void) };
        if rc != JNI_OK {
            bail!("JNI_CreateJavaVM failed with code {rc}");
        }
        let vm = unsafe { JavaVM::from_raw(vm_ptr)? };
        Ok(Jvm { vm, _lib: lib })
    }

    /// Calls `public static void main(String[])` on `class` (binary name with dots).
    /// Must run on the thread that created the JVM.
    pub fn call_main(&self, class: &str, args: &[String]) -> Result<()> {
        let mut env = self.vm.get_env()?;
        let class_internal = class.replace('.', "/");
        let cls = env
            .find_class(&class_internal)
            .with_context(|| format!("class {class} not found"))?;
        let string_cls = env.find_class("java/lang/String")?;
        let jargs = env.new_object_array(args.len() as i32, string_cls, JObject::null())?;
        for (i, a) in args.iter().enumerate() {
            let s = env.new_string(a)?;
            env.set_object_array_element(&jargs, i as i32, s)?;
        }
        let result =
            env.call_static_method(cls, "main", "([Ljava/lang/String;)V", &[JValue::Object(&jargs)]);
        if env.exception_check()? {
            env.exception_describe()?;
            env.exception_clear()?;
            bail!("{class}.main threw an exception");
        }
        result?;
        Ok(())
    }

    /// Blocks until all non-daemon Java threads have exited, then tears the JVM down.
    pub fn wait_for_exit(self) -> Result<()> {
        unsafe { self.vm.destroy()? };
        Ok(())
    }
}
