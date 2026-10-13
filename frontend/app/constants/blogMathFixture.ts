/** 独立的数学方言回归样例；仅 Design Showcase 使用，不写入真实博客。 */
export const BLOG_MATH_FIXTURE = String.raw`# 数学公式渲染测试

## 行内公式

勾股定理：$a^2+b^2=c^2$。

二次方程求根公式：
$x=\frac{-b\pm\sqrt{b^2-4ac}}{2a}$

## 积分

$$
\int_0^1 x^2\,dx=\frac{1}{3}
$$

## 求和

$$
\sum_{k=1}^{n}k=\frac{n(n+1)}{2}
$$

## 矩阵

$$
A=\begin{pmatrix}
1 & 2 \\
3 & 4
\end{pmatrix}
$$

## 多行公式

$$
\begin{aligned}
f(x) &= x^2+2x+1 \\
     &= (x+1)^2
\end{aligned}
$$

## 中文与长公式

$\text{面积}=\pi r^2$，$x_i^2$ 保持自然的行内排版。

$$
\underbrace{a_1+a_2+a_3+a_4+a_5+a_6+a_7+a_8+a_9+a_{10}+a_{11}+a_{12}+a_{13}+a_{14}+a_{15}}_{\text{长公式可以在窄屏内横向滚动}}
$$

## 安全降级

错误公式：$\notAValidCommand{<img src=x onerror=alert(1)>}$。

禁止不可信链接：$\href{javascript:alert(1)}{点击}$。

代码中的美元符号不解析：

` + "```typescript [Dollar.ts]\nconst formula = '$a^2+b^2=c^2$'\n```\n"
