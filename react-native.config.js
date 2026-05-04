module.exports = {
  dependency: {
    platforms: {
      android: {
        sourceDir: 'android',
        packageImportPath: 'import com.openclaw.runtime.OpenClawProcessPackage;',
        packageInstance: 'new OpenClawProcessPackage()',
      },
    },
  },
};
