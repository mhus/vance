require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name = 'VanceFaceliftAccountWebview'
  s.version = package['version']
  s.summary = package['description']
  s.license = package['license']
  s.homepage = 'https://github.com/mhus/vance'
  s.author = { 'Mike Hummel' => 'mh@mhus.de' }
  s.source = { :git => 'https://github.com/mhus/vance.git', :tag => s.version.to_s }
  s.source_files = 'ios/Sources/**/*.{swift,m,h}'
  s.ios.deployment_target = '17.0'
  s.dependency 'Capacitor'
  s.swift_version = '5.1'
end
