'use strict';

// A raw LINE SEPARATOR or PARAGRAPH SEPARATOR (code points 0x2028 and 0x2029) is a line terminator to javac, and
// some web editors turn it into a newline. That broke MainActivity.java ("illegal line end in character literal")
// and the APK build. This file builds the characters from their code points so it never contains them itself.
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');

const repoRoot=path.join(__dirname,'..','..');
const SEPARATORS=new RegExp('['+String.fromCharCode(0x2028,0x2029)+']');
const SKIP=new Set(['.git','node_modules','build','.gradle','__pycache__','.venv']);
const TEXT=/\.(java|kt|js|cjs|mjs|html|css|xml|gradle|py|md|yml|yaml|json|webmanifest|txt)$/i;

function files(dir){
  return fs.readdirSync(dir,{withFileTypes:true}).flatMap(entry=>{
    if(SKIP.has(entry.name))return[];
    const full=path.join(dir,entry.name);
    return entry.isDirectory()?files(full):TEXT.test(entry.name)?[full]:[];
  });
}
const sources=files(repoRoot);
const relative=file=>path.relative(repoRoot,file).split(path.sep).join('/');

test('the scan covers the app, tests and workflow sources',()=>{
  const names=sources.map(relative);
  for(const expected of ['mk-promises/web/index.html','mk-promises/android/app/src/main/java/il/mkpromises/MainActivity.java','.github/workflows/android.yml'])assert.ok(names.includes(expected),expected);
});

test('no source file contains a raw line or paragraph separator',()=>{
  const offenders=[];
  for(const file of sources){
    fs.readFileSync(file,'utf8').split('\n').forEach((line,index)=>{if(SEPARATORS.test(line))offenders.push(relative(file)+':'+(index+1))});
  }
  assert.deepEqual(offenders,[],'write them as an escape inside a string, or as a numeric case label such as 0x2028, never as the raw character');
});

test('Java sources contain no active unicode escape for a line terminator',()=>{
  // Java translates unicode escapes before lexing, so an escape for a line feed, carriage return, or either
  // separator ends the line even inside a string or a comment. An escape is active when its backslash is
  // preceded by an even number of backslashes.
  const backslash='\\\\';
  const active=new RegExp('(?<!'+backslash+')(?:'+backslash+backslash+')*'+backslash+'u+(?:000[aAdD]|202[89])');
  const offenders=sources.filter(file=>file.endsWith('.java')).filter(file=>active.test(fs.readFileSync(file,'utf8'))).map(relative);
  assert.deepEqual(offenders,[]);
  // the detector itself: an active escape is found, an escaped backslash followed by u-text is not
  const bs=String.fromCharCode(92);
  assert.ok(active.test('x = "'+bs+'u2028";'));
  assert.ok(!active.test('x = "'+bs+bs+'u2028";'));
});
